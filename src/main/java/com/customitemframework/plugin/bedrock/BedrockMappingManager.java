package com.customitemframework.plugin.bedrock;

import com.customitemframework.plugin.model.ArmorDefinition;
import com.customitemframework.plugin.model.ItemDefinition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Everything on the Bedrock/Geyser side of this plugin that CAN legally run
 * inside a normal Paper plugin.
 * <p>
 * What this class does NOT do, on purpose: subscribe to
 * {@code GeyserDefineCustomItemsEvent}. That event is fired on Geyser's own
 * extension event bus, only for classes Geyser itself loads from its
 * {@code extensions/} folder - a Bukkit {@code JavaPlugin} is never scanned
 * for {@code @Subscribe} methods, so a listener written here would compile
 * but never run. That listener lives in the sibling {@code geyser-extension}
 * Maven module, in {@code CustomItemFrameworkExtension.java}, and it gets its
 * data from the hand-off file {@link #writeExtensionHandoffFile} produces
 * below rather than a live method call, because the two artifacts are loaded
 * by two different loaders with no guaranteed order between them.
 * <p>
 * This class covers the two integration paths Geyser documents for custom
 * items (see https://geysermc.org/wiki/geyser/custom-items/):
 * <ol>
 *     <li>The no-code path: a legacy-format JSON mappings file Geyser reads
 *     directly at ITS boot ({@link #writeCustomItemMappings}). This is the
 *     one to rely on if you don't also want to install the Geyser extension.</li>
 *     <li>The hand-off file for the optional Geyser extension
 *     ({@link #writeExtensionHandoffFile}), which lets a Bedrock player see
 *     mapping changes without a full Geyser restart if you choose to install it.</li>
 * </ol>
 * Leave BOTH enabled and Geyser will simply see the same items registered
 * twice and log a harmless warning - see config.yml's
 * {@code bedrock.write-extension-handoff-file} comment.
 */
public final class BedrockMappingManager {

    private static final String NAMESPACE = "customitemframework";

    /** Standard vanilla Bedrock humanoid armor geometry ids - unchanged for years, but verify against wiki.bedrock.dev/items/custom-armor if a piece renders with the wrong silhouette on a future version. */
    private static final Map<EquipmentSlot, String> VANILLA_GEOMETRY = Map.of(
            EquipmentSlot.HEAD, "geometry.humanoid.armor.helmet",
            EquipmentSlot.CHEST, "geometry.humanoid.armor.chestplate",
            EquipmentSlot.LEGS, "geometry.humanoid.armor.leggings",
            EquipmentSlot.FEET, "geometry.humanoid.armor.boots"
    );

    private final Plugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File geyserMappingsFolder;
    private final File geyserPacksFolder;
    private final boolean writeHandoffFile;

    public BedrockMappingManager(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.geyserMappingsFolder = resolveRelative(config.getString("bedrock.geyser-mappings-folder", "../Geyser-Spigot/custom_mappings"));
        this.geyserPacksFolder = resolveRelative(config.getString("bedrock.geyser-packs-folder", "../Geyser-Spigot/packs"));
        this.writeHandoffFile = config.getBoolean("bedrock.write-extension-handoff-file", true);
    }

    private File resolveRelative(String configuredPath) {
        return new File(plugin.getDataFolder(), configuredPath).getAbsoluteFile();
    }

    // -----------------------------------------------------------------
    // 1. Legacy-format JSON mappings file (no extension required)
    // -----------------------------------------------------------------

    /**
     * Writes {@code customitemframework_mappings.json} into Geyser's
     * mappings folder using the long-standing legacy custom-model-data
     * mapping schema. Geyser reads every file in that folder at its own
     * boot - restart Geyser-Spigot (not just this plugin) after this runs
     * for changes to take effect.
     * <p>
     * <b>Confirm the folder name and schema against your installed
     * Geyser-Spigot version before relying on this in production</b> - the
     * mappings location and format have changed across Geyser releases
     * (v1 vs the newer item_model-based format); this targets the legacy
     * schema Geyser's own docs say "continues to be supported."
     */
    public File writeCustomItemMappings(List<ItemDefinition> items, List<ArmorDefinition> armorSets) throws IOException {
        if (!geyserMappingsFolder.exists() && !geyserMappingsFolder.mkdirs()) {
            throw new IOException("Could not create Geyser mappings folder at " + geyserMappingsFolder);
        }

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1");

        // Groups every custom item/armor-piece by ITS OWN base Java item id,
        // since that's how the legacy schema keys entries.
        Map<String, JsonArray> byBaseItem = new LinkedHashMap<>();

        for (ItemDefinition item : items) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", NAMESPACE + ":" + item.id());
            entry.addProperty("custom_model_data", item.customModelData());
            entry.addProperty("icon", item.id());
            entry.addProperty("display_name", stripLegacyColorCodes(item.displayName()));
            entry.addProperty("is_tool", item.isTool());
            byBaseItem.computeIfAbsent("minecraft:" + item.material().getKey().getKey(), k -> new JsonArray()).add(entry);
        }

        for (ArmorDefinition armor : armorSets) {
            for (EquipmentSlot slot : ArmorDefinition.SLOT_ORDER) {
                var material = armor.equipSlotMaterials().get(slot);
                if (material == null) continue;
                JsonObject entry = new JsonObject();
                String pieceId = armor.id() + "_" + slot.name().toLowerCase();
                entry.addProperty("name", NAMESPACE + ":" + pieceId);
                entry.addProperty("custom_model_data", armor.customModelDataFor(slot));
                entry.addProperty("icon", pieceId);
                entry.addProperty("display_name", stripLegacyColorCodes(armor.displayName()));
                entry.addProperty("is_tool", false);
                byBaseItem.computeIfAbsent("minecraft:" + material.getKey().getKey(), k -> new JsonArray()).add(entry);
            }
        }

        JsonObject itemsNode = new JsonObject();
        byBaseItem.forEach(itemsNode::add);
        root.add("items", itemsNode);

        File outputFile = new File(geyserMappingsFolder, "customitemframework_mappings.json");
        Files.writeString(outputFile.toPath(), gson.toJson(root), StandardCharsets.UTF_8);
        return outputFile;
    }

    // -----------------------------------------------------------------
    // 2. Bedrock attachables (worn-body rendering)
    // -----------------------------------------------------------------

    /**
     * Writes one {@code minecraft:attachable} JSON per armor piece into
     * {@code <stagingDir>/attachables/}. Every piece points its "geometry"
     * at the VANILLA humanoid armor mesh and only swaps "textures" - see
     * the README for why arbitrary 3D armor silhouettes aren't possible on
     * Bedrock (or Java) through a resource pack alone. The "materials"/
     * enchant-glint wiring below mirrors vanilla's own attachables so
     * enchanted custom armor still gets the glint effect instead of looking
     * un-enchanted.
     */
    public void writeArmorAttachables(File bedrockStagingDir, List<ArmorDefinition> armorSets) throws IOException {
        File attachablesDir = new File(bedrockStagingDir, "attachables");
        if (!attachablesDir.exists() && !attachablesDir.mkdirs()) {
            throw new IOException("Could not create attachables staging folder at " + attachablesDir);
        }

        for (ArmorDefinition armor : armorSets) {
            for (EquipmentSlot slot : ArmorDefinition.SLOT_ORDER) {
                if (!armor.equipSlotMaterials().containsKey(slot)) continue;
                String pieceId = armor.id() + "_" + slot.name().toLowerCase();
                String json = gson.toJson(buildAttachable(armor, slot, pieceId));
                Files.writeString(new File(attachablesDir, pieceId + ".json").toPath(), json, StandardCharsets.UTF_8);
            }
        }
    }

    private JsonObject buildAttachable(ArmorDefinition armor, EquipmentSlot slot, String pieceId) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");

        JsonObject attachable = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("identifier", NAMESPACE + ":" + pieceId);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "armor");
        materials.addProperty("enchanted", "armor_enchanted");
        description.add("materials", materials);

        // layer_1 (helmet/chestplate/boots) vs layer_2 (leggings) tracks the
        // SAME split Java has always used for armor texture layers - see
        // TextureProcessor's humanoid / humanoid_leggings distinction.
        String textureRef = (slot == EquipmentSlot.LEGS)
                ? "textures/models/armor/" + armor.id() + "_layer_2"
                : "textures/models/armor/" + armor.id() + "_layer_1";

        JsonObject textures = new JsonObject();
        textures.addProperty("default", textureRef);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", VANILLA_GEOMETRY.get(slot));
        description.add("geometry", geometry);

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.armor");
        description.add("render_controllers", renderControllers);

        attachable.add("description", description);
        root.add("minecraft:attachable", attachable);
        return root;
    }

    // -----------------------------------------------------------------
    // 3. item_texture.json (Bedrock icon atlas registration)
    // -----------------------------------------------------------------

    public void writeItemTextureJson(File bedrockStagingDir, List<ItemDefinition> items, List<ArmorDefinition> armorSets,
                                      String packName) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", packName);
        root.addProperty("texture_name", "atlas.items");

        JsonObject textureData = new JsonObject();
        for (ItemDefinition item : items) {
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", "textures/items/" + item.id());
            textureData.add(item.id(), entry);
        }
        for (ArmorDefinition armor : armorSets) {
            for (EquipmentSlot slot : ArmorDefinition.SLOT_ORDER) {
                if (!armor.equipSlotMaterials().containsKey(slot)) continue;
                String pieceId = armor.id() + "_" + slot.name().toLowerCase();
                JsonObject entry = new JsonObject();
                // Armor inventory icons reuse layer_1 as a flat icon - swap
                // this for a dedicated icon texture if you want the two to differ.
                entry.addProperty("textures", "textures/models/armor/" + armor.id() + "_layer_1");
                textureData.add(pieceId, entry);
            }
        }
        root.add("texture_data", textureData);

        File textureDir = new File(bedrockStagingDir, "textures");
        textureDir.mkdirs();
        Files.writeString(new File(textureDir, "item_texture.json").toPath(), gson.toJson(root), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------
    // 4. manifest.json + final .mcpack assembly
    // -----------------------------------------------------------------

    public void writeManifest(File bedrockStagingDir, String packName, String description) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);

        JsonObject header = new JsonObject();
        header.addProperty("name", packName);
        header.addProperty("description", description);
        header.addProperty("uuid", stableUuidFor(packName + "-header").toString());
        header.add("version", versionArray(1, 0, 0));
        header.add("min_engine_version", versionArray(1, 21, 0));
        root.add("header", header);

        JsonArray modules = new JsonArray();
        JsonObject resourceModule = new JsonObject();
        resourceModule.addProperty("type", "resources");
        resourceModule.addProperty("uuid", stableUuidFor(packName + "-module").toString());
        resourceModule.add("version", versionArray(1, 0, 0));
        modules.add(resourceModule);
        root.add("modules", modules);

        Files.writeString(new File(bedrockStagingDir, "manifest.json").toPath(), gson.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Deterministic per-pack-name UUID (rather than a fresh random one every
     * boot) so Bedrock clients don't treat every server restart as a brand
     * new pack needing a full re-download. Uses UUID.nameUUIDFromBytes,
     * which is a stable, standard JDK method for exactly this purpose.
     */
    private UUID stableUuidFor(String seed) {
        return UUID.nameUUIDFromBytes((NAMESPACE + ":" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private JsonArray versionArray(int major, int minor, int patch) {
        JsonArray array = new JsonArray();
        array.add(major);
        array.add(minor);
        array.add(patch);
        return array;
    }

    /**
     * Zips {@code bedrockStagingDir} (manifest.json + textures/ + attachables/,
     * already populated by TextureProcessor.stageBedrockTextures and the
     * methods above) into {@code outputMcpack}. A .mcpack is just a .zip
     * with manifest.json at its root - no special container format beyond that.
     */
    public File assembleMcpack(File bedrockStagingDir, File outputMcpack) throws IOException {
        outputMcpack.getParentFile().mkdirs();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputMcpack.toPath()))) {
            Path root = bedrockStagingDir.toPath();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                    zip.putNextEntry(new ZipEntry(relative));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
        return outputMcpack;
    }

    /** Copies the finished .mcpack into Geyser-Spigot's local packs folder so it's served to Bedrock clients on their next join. */
    public void deployToGeyserPacksFolder(File mcpack) throws IOException {
        if (!geyserPacksFolder.exists() && !geyserPacksFolder.mkdirs()) {
            throw new IOException("Could not create Geyser packs folder at " + geyserPacksFolder);
        }
        Files.copy(mcpack.toPath(), new File(geyserPacksFolder, mcpack.getName()).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // -----------------------------------------------------------------
    // 5. Hand-off file for the optional geyser-extension module
    // -----------------------------------------------------------------

    /**
     * Writes a small, stable JSON contract describing every registered item
     * and armor piece, read by {@code CustomItemFrameworkExtension} in the
     * sibling {@code geyser-extension} module at ITS boot (see that file's
     * javadoc for the read side of this contract). This takes the place of
     * a direct method call across the plugin/extension boundary, because
     * nothing guarantees this plugin and that extension are even loaded by
     * the same process in that order - a shared file is the standard,
     * dependency-free way two independently-loaded artifacts like this hand
     * data to each other.
     */
    public File writeExtensionHandoffFile(List<ItemDefinition> items, List<ArmorDefinition> armorSets) throws IOException {
        if (!writeHandoffFile) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 1);
        root.addProperty("namespace", NAMESPACE);

        JsonArray itemsArray = new JsonArray();
        for (ItemDefinition item : items) {
            JsonObject o = new JsonObject();
            o.addProperty("id", item.id());
            o.addProperty("baseJavaItem", "minecraft:" + item.material().getKey().getKey());
            o.addProperty("customModelData", item.customModelData());
            o.addProperty("displayName", stripLegacyColorCodes(item.displayName()));
            o.addProperty("isTool", item.isTool());
            itemsArray.add(o);
        }
        root.add("items", itemsArray);

        JsonArray armorArray = new JsonArray();
        for (ArmorDefinition armor : armorSets) {
            for (EquipmentSlot slot : ArmorDefinition.SLOT_ORDER) {
                var material = armor.equipSlotMaterials().get(slot);
                if (material == null) continue;
                String pieceId = armor.id() + "_" + slot.name().toLowerCase();
                JsonObject o = new JsonObject();
                o.addProperty("id", pieceId);
                o.addProperty("slot", slot.name());
                o.addProperty("baseJavaItem", "minecraft:" + material.getKey().getKey());
                o.addProperty("customModelData", armor.customModelDataFor(slot));
                o.addProperty("displayName", stripLegacyColorCodes(armor.displayName()));
                o.addProperty("attachableIdentifier", NAMESPACE + ":" + pieceId);
                armorArray.add(o);
            }
        }
        root.add("armorPieces", armorArray);

        File dataFolder = plugin.getDataFolder();
        File handoff = new File(dataFolder, "output/geyser-extension-handoff.json");
        handoff.getParentFile().mkdirs();
        Files.writeString(handoff.toPath(), gson.toJson(root), StandardCharsets.UTF_8);
        return handoff;
    }

    private static String stripLegacyColorCodes(String s) {
        return s == null ? "" : s.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }
}
