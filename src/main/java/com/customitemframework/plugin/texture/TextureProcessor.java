package com.customitemframework.plugin.texture;

import com.customitemframework.plugin.model.ArmorDefinition;
import com.customitemframework.plugin.model.ItemDefinition;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Owns the entire texture pipeline: validating raw AI PNGs, assembling the
 * full-resolution Java resource pack, and producing downscaled copies for
 * the Bedrock .mcpack ({@link com.customitemframework.plugin.bedrock.BedrockMappingManager}
 * does the Bedrock-specific JSON/manifest work; this class only owns pixels
 * and the Java-side pack).
 *
 * <h2>Memory note</h2>
 * Boot-time processing of many 1024x1024 PNGs is the one place this plugin
 * can actually hurt your heap if done carelessly (a decoded 1024x1024 ARGB
 * BufferedImage is ~4MB per copy). Every method below processes one texture
 * at a time and writes it straight to its destination (zip entry or PNG
 * file) before moving on - nothing keeps a list of decoded BufferedImages
 * around. If you have hundreds of 1024x1024 textures, boot will still take
 * real wall-clock time (image decode + bicubic resample + PNG re-encode is
 * not free) - {@code logging.verbose-boot-scan: true} in config.yml will
 * show you where that time goes.
 */
public final class TextureProcessor {

    public enum TextureKind { ITEM_ICON, ARMOR_LAYER }

    public enum Interpolation {
        BILINEAR(RenderingHints.VALUE_INTERPOLATION_BILINEAR),
        BICUBIC(RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        final Object hintValue;
        Interpolation(Object hintValue) { this.hintValue = hintValue; }
    }

    /** Thrown for any texture that fails validation; the caller decides whether that's fatal or just "skip this item". */
    public static final class TextureValidationException extends Exception {
        public TextureValidationException(String message) { super(message); }
    }

    private final Plugin plugin;
    private final File rawTextureFolder;
    private final int maxSourceResolution;
    private final boolean requireSquareIcons;
    private final boolean requirePowerOfTwo;
    private final int bedrockMaxResolution;
    private final Interpolation interpolation;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String NAMESPACE = "customitemframework";

    public TextureProcessor(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.rawTextureFolder = new File(plugin.getDataFolder(), config.getString("textures.source-folder", "textures/raw"));
        this.maxSourceResolution = config.getInt("textures.max-source-resolution", 1024);
        this.requireSquareIcons = config.getBoolean("textures.require-square", true);
        this.requirePowerOfTwo = config.getBoolean("textures.require-power-of-two", true);
        this.bedrockMaxResolution = config.getInt("bedrock.max-resolution", 256);
        this.interpolation = Interpolation.valueOf(config.getString("bedrock.interpolation", "BICUBIC").toUpperCase());

        if (!rawTextureFolder.exists() && !rawTextureFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create textures folder at " + rawTextureFolder.getAbsolutePath());
        }
    }

    public File rawTextureFolder() {
        return rawTextureFolder;
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    /**
     * Reads just the PNG header (via ImageIO's ImageReader, not a full
     * decode) to get dimensions cheaply, then applies the rule set for the
     * given {@link TextureKind}.
     * <p>
     * Item icons: square, optionally power-of-two (soft, config-controlled
     * best practice, not a hard Minecraft requirement).
     * <p>
     * Armor layers: MUST be exactly 2:1 width:height. This is not a style
     * choice - it is Minecraft's fixed armor-layer UV template (the classic
     * 64x32 layout, or any uniform upscale of it like 256x128). A texture
     * that isn't 2:1 will UV-map onto the humanoid armor mesh incorrectly
     * (stretched/shifted), regardless of resolution.
     */
    public int[] validate(String filename, TextureKind kind) throws TextureValidationException, IOException {
        File file = new File(rawTextureFolder, filename);
        if (!file.exists()) {
            throw new TextureValidationException("texture file not found: " + file.getAbsolutePath());
        }
        int[] dims = readDimensions(file);
        int width = dims[0];
        int height = dims[1];

        if (Math.max(width, height) > maxSourceResolution) {
            throw new TextureValidationException(filename + " is " + width + "x" + height
                    + ", which exceeds textures.max-source-resolution (" + maxSourceResolution + ")");
        }

        switch (kind) {
            case ITEM_ICON -> {
                if (requireSquareIcons && width != height) {
                    throw new TextureValidationException(filename + " must be square for an item icon, got "
                            + width + "x" + height);
                }
                if (requirePowerOfTwo && (!isPowerOfTwo(width) || !isPowerOfTwo(height))) {
                    throw new TextureValidationException(filename
                            + " is not a power-of-two resolution (" + width + "x" + height
                            + ") - disable textures.require-power-of-two if this is intentional");
                }
            }
            case ARMOR_LAYER -> {
                if (width != height * 2) {
                    throw new TextureValidationException(filename
                            + " must keep Minecraft's fixed 2:1 armor-layer template ratio (e.g. 64x32, 256x128, 512x256), got "
                            + width + "x" + height);
                }
                if (requirePowerOfTwo && !isPowerOfTwo(height)) {
                    throw new TextureValidationException(filename
                            + " height (" + height + ") is not a power of two - disable textures.require-power-of-two if intentional");
                }
            }
        }
        return dims;
    }

    private int[] readDimensions(File file) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No PNG reader available for " + file.getName() + " - is it actually a PNG?");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    // -----------------------------------------------------------------
    // Scaling
    // -----------------------------------------------------------------

    /**
     * Resamples {@code source} to exactly {@code targetWidth}x{@code targetHeight}
     * using the configured interpolation. Deliberately takes width AND height
     * (not one "size") because armor layers are 2:1, not square.
     */
    public BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation.hintValue);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return output;
    }

    /** Bedrock dimensions for a given source size: capped at bedrock.max-resolution, aspect ratio preserved, never upscaled. */
    public int[] bedrockTargetDimensions(int sourceWidth, int sourceHeight) {
        int cap = bedrockMaxResolution;
        if (sourceWidth <= cap && sourceHeight <= cap) {
            return new int[]{sourceWidth, sourceHeight};
        }
        double scale = Math.min((double) cap / sourceWidth, (double) cap / sourceHeight);
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new int[]{w, h};
    }

    // -----------------------------------------------------------------
    // Java resource pack assembly
    // -----------------------------------------------------------------

    /**
     * Builds the complete Java Edition resource pack zip at {@code outputZip},
     * containing full-resolution textures, per-item model JSON, merged
     * vanilla base-item overrides, and (for armor) the 1.21.2+ equipment
     * asset JSON + humanoid/humanoid_leggings textures.
     *
     * @return the number of textures actually written (for logging).
     */
    public int buildJavaResourcePack(File outputZip, List<ItemDefinition> items, List<ArmorDefinition> armorSets,
                                      String packDescription) throws IOException {
        outputZip.getParentFile().mkdirs();
        int written = 0;

        // Base-item model overrides accumulate across items that might share
        // a base Material (e.g. two different swords both riding on
        // DIAMOND_SWORD) - so we batch all the JSON edits and flush once.
        Map<String, JsonArray> baseItemOverrides = new HashMap<>();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputZip.toPath()))) {
            writeEntry(zip, "pack.mcmeta", packMcMeta(packDescription).getBytes(StandardCharsets.UTF_8));

            for (ItemDefinition item : items) {
                File raw = new File(rawTextureFolder, item.textureFile());
                BufferedImage image = readImage(raw);
                try {
                    // 1. our namespaced texture, full resolution, untouched
                    writeEntry(zip, "assets/" + NAMESPACE + "/textures/item/" + item.id() + ".png", encodePng(image));

                    // 2. our namespaced item model (either the caller's hand-authored
                    //    model JSON, copied through as-is, or the vanilla flat-icon parent)
                    if (item.modelPath() != null) {
                        File modelFile = new File(plugin.getDataFolder(), "textures/models/" + item.modelPath());
                        if (modelFile.exists()) {
                            writeEntry(zip, "assets/" + NAMESPACE + "/models/item/" + item.id() + ".json",
                                    Files.readAllBytes(modelFile.toPath()));
                        } else {
                            plugin.getLogger().warning("Item '" + item.id() + "' references model '" + item.modelPath()
                                    + "' which does not exist - falling back to the flat-icon parent model.");
                            writeEntry(zip, "assets/" + NAMESPACE + "/models/item/" + item.id() + ".json",
                                    flatIconModel(item).getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        writeEntry(zip, "assets/" + NAMESPACE + "/models/item/" + item.id() + ".json",
                                flatIconModel(item).getBytes(StandardCharsets.UTF_8));
                    }

                    // 3. queue this item as an override on its vanilla base item
                    String baseKey = item.material().getKey().getKey(); // e.g. "diamond_sword"
                    JsonObject predicate = new JsonObject();
                    predicate.addProperty("custom_model_data", item.customModelData());
                    JsonObject override = new JsonObject();
                    override.add("predicate", predicate);
                    override.addProperty("model", item.modelKey());
                    baseItemOverrides.computeIfAbsent(baseKey, k -> new JsonArray()).add(override);

                    written++;
                } finally {
                    image.flush();
                }
            }

            for (ArmorDefinition armor : armorSets) {
                BufferedImage layer1 = readImage(new File(rawTextureFolder, armor.layer1TextureFile()));
                BufferedImage layer2 = readImage(new File(rawTextureFolder, armor.layer2TextureFile()));
                try {
                    writeEntry(zip, "assets/" + NAMESPACE + "/textures/entity/equipment/humanoid/" + armor.id() + ".png",
                            encodePng(layer1));
                    writeEntry(zip, "assets/" + NAMESPACE + "/textures/entity/equipment/humanoid_leggings/" + armor.id() + ".png",
                            encodePng(layer2));
                    writeEntry(zip, "assets/" + NAMESPACE + "/equipment/" + armor.id() + ".json",
                            equipmentAssetJson(armor).getBytes(StandardCharsets.UTF_8));
                    written += 2;
                } finally {
                    layer1.flush();
                    layer2.flush();
                }
            }

            // Flush merged base-item override files. These live in the
            // *minecraft* namespace because we are editing vanilla_item.json,
            // not creating a new one - Minecraft only reads one model file
            // per vanilla item id, so this must be a full merge, never an
            // overwrite, if the user's other plugins/packs also touch it.
            for (Map.Entry<String, JsonArray> entry : baseItemOverrides.entrySet()) {
                writeEntry(zip, "assets/minecraft/models/item/" + entry.getKey() + ".json",
                        baseItemModel(entry.getKey(), entry.getValue()).getBytes(StandardCharsets.UTF_8));
            }
        }

        return written;
    }

    private String packMcMeta(String description) {
        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 46); // 1.21.4 resource pack format - bump if you target a newer version
        pack.addProperty("description", description);
        root.add("pack", pack);
        return gson.toJson(root);
    }

    private String flatIconModel(ItemDefinition item) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", NAMESPACE + ":item/" + item.id());
        root.add("textures", textures);
        return gson.toJson(root);
    }

    /**
     * Rebuilds assets/minecraft/models/item/&lt;base&gt;.json with the vanilla
     * default parent/texture PLUS our accumulated overrides. If you run this
     * plugin alongside another plugin that ALSO edits this same base item's
     * model (ItemsAdder, Oraxen, etc.), you must merge packs at the resource
     * pack level (a "combine resource packs" step) rather than loading both
     * independently - only one file wins otherwise. See README.
     */
    private String baseItemModel(String baseItemId, JsonArray overrides) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", "minecraft:item/" + baseItemId);
        root.add("textures", textures);
        root.add("overrides", overrides);
        return gson.toJson(root);
    }

    private String equipmentAssetJson(ArmorDefinition armor) {
        JsonObject root = new JsonObject();
        JsonObject layers = new JsonObject();

        JsonArray humanoid = new JsonArray();
        JsonObject humanoidLayer = new JsonObject();
        humanoidLayer.addProperty("texture", NAMESPACE + ":" + armor.id());
        humanoid.add(humanoidLayer);
        layers.add("humanoid", humanoid);

        JsonArray humanoidLeggings = new JsonArray();
        JsonObject leggingsLayer = new JsonObject();
        leggingsLayer.addProperty("texture", NAMESPACE + ":" + armor.id());
        humanoidLeggings.add(leggingsLayer);
        layers.add("humanoid_leggings", humanoidLeggings);

        root.add("layers", layers);
        return gson.toJson(root);
    }

    // -----------------------------------------------------------------
    // Bedrock staging (downscaled copies only - Bedrock JSON/manifest is
    // BedrockMappingManager's job, this just hands it correctly-sized PNGs)
    // -----------------------------------------------------------------

    /**
     * Writes a downscaled copy of every referenced texture into
     * {@code bedrockStagingDir}, mirroring the same relative layout
     * BedrockMappingManager expects when it assembles the .mcpack:
     * textures/items/&lt;id&gt;.png and textures/models/armor/&lt;id&gt;_layer_1.png / _layer_2.png.
     */
    public void stageBedrockTextures(File bedrockStagingDir, List<ItemDefinition> items, List<ArmorDefinition> armorSets)
            throws IOException {
        File itemsDir = new File(bedrockStagingDir, "textures/items");
        File armorDir = new File(bedrockStagingDir, "textures/models/armor");
        itemsDir.mkdirs();
        armorDir.mkdirs();

        for (ItemDefinition item : items) {
            downscaleToFile(new File(rawTextureFolder, item.textureFile()), new File(itemsDir, item.id() + ".png"));
        }
        for (ArmorDefinition armor : armorSets) {
            downscaleToFile(new File(rawTextureFolder, armor.layer1TextureFile()), new File(armorDir, armor.id() + "_layer_1.png"));
            downscaleToFile(new File(rawTextureFolder, armor.layer2TextureFile()), new File(armorDir, armor.id() + "_layer_2.png"));
        }
    }

    private void downscaleToFile(File source, File dest) throws IOException {
        BufferedImage image = readImage(source);
        try {
            int[] target = bedrockTargetDimensions(image.getWidth(), image.getHeight());
            BufferedImage scaled = (target[0] == image.getWidth() && target[1] == image.getHeight())
                    ? image
                    : scale(image, target[0], target[1]);
            try {
                dest.getParentFile().mkdirs();
                ImageIO.write(scaled, "png", dest);
            } finally {
                if (scaled != image) {
                    scaled.flush();
                }
            }
        } finally {
            image.flush();
        }
    }

    // -----------------------------------------------------------------
    // Small IO helpers
    // -----------------------------------------------------------------

    private BufferedImage readImage(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("Referenced texture does not exist: " + file.getAbsolutePath());
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Could not decode " + file.getAbsolutePath() + " as an image (corrupt or not a PNG?)");
        }
        return image;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    /** Tracks which entries have already been written so a duplicate path (e.g. two armor sets, same id typo) fails loudly instead of corrupting the zip. */
    private final Deque<String> writtenEntries = new ArrayDeque<>();

    private void writeEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        if (writtenEntries.contains(path)) {
            plugin.getLogger().warning("Duplicate resource pack entry '" + path
                    + "' - check for duplicate ids in items.yml. Overwriting the previous copy.");
        } else {
            writtenEntries.add(path);
        }
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
