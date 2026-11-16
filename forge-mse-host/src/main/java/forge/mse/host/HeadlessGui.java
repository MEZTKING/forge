package forge.mse.host;

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
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

/**
 * Forge without a window: everything the engine (and the bridge) asks of a GUI is answered here, quietly.
 *
 * <p>The bridge has its own {@link forge.mse.bridge.BridgeGui} (a subclass of this); the headless module
 * means the bridge and the bot-game simulator run from a jar with no Swing desktop on the classpath.</p>
 */
public class HeadlessGui implements IGuiBase {
    /** The one thread that stands in for Swing's event dispatch thread. */
    private static final java.util.concurrent.ExecutorService EDT =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "MSE-EDT");
                t.setDaemon(true);
                return t;
            });

    @Override
    public boolean isRunningOnDesktop() {
        return false;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return BuildInfo.getVersionString();
    }

    @Override
    public void invokeInEdtNow(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtLater(final Runnable runnable) {
        EDT.submit(runnable);
    }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        try {
            EDT.submit(proc).get();
        } catch (final java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {
        task.run();
    }

    @Override
    public boolean isGuiThread() {
        // Our own single thread stands in for Swing's event dispatch thread (see EDT above).
        return "MSE-EDT".equals(Thread.currentThread().getName());
    }

    @Override
    public String getAssetsDir() {
        return "";
    }

    @Override
    public ImageFetcher getImageFetcher() {
        return null;
    }

    @Override
    public ISkinImage getSkinIcon(final FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(final String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(final PaperCard card, final boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(final PaperCard paperCard, final FSkinProp background,
            final String overlayFilename, final float opacity) {
        return null;
    }

    @Override
    public void clearImageCache() {
    }

    @Override
    public String encodeSymbols(final String str, final boolean formatReminderText) {
        return str;
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public float getScreenScale() {
        return 1f;
    }

    @Override
    public void preventSystemSleep(final boolean preventSleep) {
    }

    @Override
    public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(false);
        }
    }

    @Override
    public void copyToClipboard(final String text) {
    }

    @Override
    public void browseToUrl(final String url) throws IOException, URISyntaxException {
    }

    @Override
    public void showCardList(final String title, final String message, final List<PaperCard> list) {
    }

    @Override
    public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
        return false;
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println(title + "\n" + text);
    }

    @Override
    public void showImageDialog(final ISkinImage image, final String message, final String title) {
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
            final List<String> options, final int defaultOption) {
        return defaultOption;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
            final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return initialInput;
    }

    @Override
    public String showFileDialog(final String title, final String defaultDir) {
        return null;
    }

    @Override
    public File getSaveFile(final File defaultFile) {
        return defaultFile;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
        return sourceChoices == null ? new ArrayList<>() : new ArrayList<>(sourceChoices);
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices,
            final Collection<T> selected, final FSerializableFunction<T, String> display) {
        final List<T> all = choices == null ? new ArrayList<>() : new ArrayList<>(choices);
        return new ArrayList<>(all.subList(0, Math.max(0, Math.min(min, all.size()))));
    }

    @Override
    public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public boolean isSupportedAudioFormat(final File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(final String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(final String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(final String filename, final boolean isSynchronized) {
    }

    @Override
    public void showSpellShop() {
    }

    @Override
    public void showBazaar() {
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return null;
    }

    @Override
    public HostedMatch hostMatch() {
        return null;
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }
}
