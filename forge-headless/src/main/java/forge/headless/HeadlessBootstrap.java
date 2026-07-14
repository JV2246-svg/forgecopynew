package forge.headless;

import java.io.File;

import forge.gui.GuiBase;
import forge.model.FModel;

/**
 * Boots the engine with no UI and no telemetry. Must run before any engine
 * class loads: ForgeConstants reads the assets dir from the GuiBase interface
 * in a static initializer.
 */
public final class HeadlessBootstrap {
    private HeadlessBootstrap() {
    }

    public static void boot() {
        final String assetsDir = System.getProperty("forge.assets.dir", "." + File.separator);
        GuiBase.setInterface(new HeadlessGuiBase(assetsDir));
        FModel.initialize(null, null);
    }
}
