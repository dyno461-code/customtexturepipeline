package com.customitemframework.geyserext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.CustomItemData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The actual {@code GeyserDefineCustomItemsEvent} listener requested for
 * "BedrockMappingManager" - it just cannot physically live inside the Paper
 * plugin's jar (see {@code com.customitemframework.plugin.bedrock.BedrockMappingManager}'s
 * class javadoc for the full explanation). This class is loaded by GEYSER
 * itself, from Geyser-Spigot's {@code extensions/} folder, driven by
 * {@code extension.yml} sitting next to it in this module's resources -
 * NOT from the server's {@code plugins/} folder.
 *
 * <h2>Deployment</h2>
 * <pre>
 *   Geyser-Spigot/
 *     extensions/
 *       CIFGeyserExtension-1.0.0.jar   &lt;-- this module's build output
 *   plugins/
 *     CustomItemFramework-1.0.0.jar    &lt;-- the OTHER module's build output
 * </pre>
 * Restart Geyser-Spigot (a full server restart is simplest) after dropping
 * this in - {@code GeyserDefineCustomItemsEvent} only fires once, during
 * Geyser's own boot.
 *
 * <h2>Data source</h2>
 * Reads {@code plugins/CustomItemFramework/output/geyser-extension-handoff.json},
 * written by the Paper plugin's {@code BedrockMappingManager.writeExtensionHandoffFile}.
 * If that file is missing (the Paper plugin hasn't booted yet, or
 * {@code bedrock.write-extension-handoff-file} is off in its config.yml),
 * this extension logs a warning and registers nothing - it does not fail
 * Geyser's boot.
 *
 * <h2>One thing to verify yourself</h2>
 * Every import and method call below other than {@link #buildCustomItemData}
 * is taken directly from confirmed, current Geyser API documentation/source.
 * {@code CustomItemData}'s exact builder chain (as opposed to the sibling
 * {@code NonVanillaCustomItemData}, whose chain IS directly documented) is
 * reconstructed from the method signature
 * {@code register(String identifier, CustomItemData customItemData)} plus
 * Geyser's general builder conventions, not a confirmed worked example -
 * if your Geyser API version renamed a method there, that's the one spot
 * to fix; everything else in this file does not depend on it being exactly right.
 */
public final class CustomItemFrameworkExtension implements Extension {

    private static final Path HANDOFF_FILE = Path.of("plugins", "CustomItemFramework", "output", "geyser-extension-handoff.json");

    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        Logger log = logger();

        JsonObject handoff;
        try {
            handoff = readHandoffFile();
        } catch (IOException ex) {
            log.log(Level.WARNING, "CustomItemFrameworkExtension: could not read the hand-off file at "
                    + HANDOFF_FILE.toAbsolutePath()
                    + " - has the CustomItemFramework Paper plugin booted at least once? Registering nothing this boot.", ex);
            return;
        }

        int registered = 0;

        JsonArray items = handoff.getAsJsonArray("items");
        if (items != null) {
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                if (registerOne(event, item, false)) {
                    registered++;
                }
            }
        }

        JsonArray armorPieces = handoff.getAsJsonArray("armorPieces");
        if (armorPieces != null) {
            for (JsonElement el : armorPieces) {
                JsonObject piece = el.getAsJsonObject();
                if (registerOne(event, piece, true)) {
                    registered++;
                }
            }
        }

        log.info("CustomItemFrameworkExtension: registered " + registered + " custom item(s)/armor piece(s) with Geyser.");
    }

    private boolean registerOne(GeyserDefineCustomItemsEvent event, JsonObject entry, boolean isArmorPiece) {
        String id = entry.get("id").getAsString();
        String baseJavaItem = entry.get("baseJavaItem").getAsString();
        try {
            CustomItemData customItemData = buildCustomItemData(entry, isArmorPiece);
            return event.register(baseJavaItem, customItemData);
        } catch (RuntimeException ex) {
            logger().log(Level.WARNING, "CustomItemFrameworkExtension: failed to register '" + id
                    + "' (base item " + baseJavaItem + ") - see the class javadoc's 'one thing to verify' note.", ex);
            return false;
        }
    }

    /**
     * Builds the Geyser-side definition for one entry from the hand-off
     * file. See the class javadoc: this is the one method whose exact
     * method names may need a small adjustment for your exact Geyser API
     * version - everything it's given (display name, CustomModelData
     * value, whether it's a tool, and for armor the attachable identifier
     * to link the worn-body rendering to) is already correct and complete
     * from BedrockMappingManager's side.
     */
    private CustomItemData buildCustomItemData(JsonObject entry, boolean isArmorPiece) {
        String id = entry.get("id").getAsString();
        String displayName = entry.get("displayName").getAsString();
        int customModelData = entry.get("customModelData").getAsInt();
        boolean isTool = !isArmorPiece && entry.has("isTool") && entry.get("isTool").getAsBoolean();

        CustomItemBedrockOptions.Builder bedrockOptions = CustomItemBedrockOptions.builder();
        if (isArmorPiece) {
            // Purely cosmetic armor-bar display; actual protection still
            // comes from the base Java item (e.g. NETHERITE_HELMET), this
            // just keeps the Bedrock HUD icon consistent with it.
            bedrockOptions.protectionValue(0);
        }
        if (isTool) {
            bedrockOptions.displayHandheld(true);
        }

        return CustomItemData.builder()
                .name(id)
                .customModelData(customModelData)
                .displayName(displayName)
                .icon(id)
                .bedrockOptions(bedrockOptions.build())
                .build();
    }

    private JsonObject readHandoffFile() throws IOException {
        String raw = Files.readString(HANDOFF_FILE, StandardCharsets.UTF_8);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private Logger logger() {
        return Logger.getLogger("CustomItemFrameworkExtension");
    }
}
