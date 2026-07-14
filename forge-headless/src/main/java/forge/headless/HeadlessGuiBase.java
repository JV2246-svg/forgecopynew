package forge.headless;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.jupnp.UpnpServiceConfiguration;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

/**
 * Platform implementation with no UI attached. Every visual/audio hook is a
 * no-op; threading hooks run inline on the calling thread. The engine only
 * needs {@link #getAssetsDir()} to locate res/.
 */
public class HeadlessGuiBase implements IGuiBase {
    private final String assetsDir;

    public HeadlessGuiBase(final String assetsDir) {
        this.assetsDir = assetsDir.endsWith(File.separator) || assetsDir.endsWith("/")
                ? assetsDir : assetsDir + File.separator;
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return "headless"; }
    @Override public void invokeInEdtNow(final Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtLater(final Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtAndWait(final Runnable proc) { proc.run(); }
    @Override public void runBackgroundTask(final String message, final Runnable task) { task.run(); }
    // false so InputSyncronizedBase's "must not block the EDT" assert passes:
    // headless has no EDT, all invokeInEdt* hooks run inline on the caller
    @Override public boolean isGuiThread() { return false; }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
    @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background, final String overlayFilename, final float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public void refreshSkin() { }
    @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1.0f; }
    @Override public void preventSystemSleep(final boolean preventSleep) { }
    @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(final String text) { }
    @Override public void browseToUrl(final String url) throws IOException, URISyntaxException { }
    @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) { }
    @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
    @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) { return defaultOption; }
    @Override public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) { return initialInput; }
    @Override public String showFileDialog(final String title, final String defaultDir) { return defaultDir; }
    @Override public File getSaveFile(final File defaultFile) { return defaultFile; }
    @Override public <T> List<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) { return destChoices; }
    @Override public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices, final Collection<T> selected, final FSerializableFunction<T, String> display) { return new ArrayList<>(selected); }
    @Override public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) { return list.isEmpty() ? null : list.get(0); }
    @Override public boolean isSupportedAudioFormat(final File file) { return false; }
    @Override public IAudioClip createAudioClip(final String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
    @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return null; }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
