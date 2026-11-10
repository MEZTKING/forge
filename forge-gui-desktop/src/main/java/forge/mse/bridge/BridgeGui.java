package forge.mse.bridge;

import java.util.List;

import forge.GuiDesktop;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;

/**
 * Forge's desktop interface with its game window swapped for the bridge: a game Forge would
 * open a window for gets the {@link JsonGuiGame} instead, and nothing here opens a dialog.
 */
public class BridgeGui extends GuiDesktop {
    private volatile JsonGuiGame gui;

    public void setGui(final JsonGuiGame gui0) {
        gui = gui0;
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return gui;
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println(title + "\n" + text);
        final JsonGuiGame g = gui;
        if (g != null) {
            g.error(title + ": " + firstLines(text));
        }
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options,
            final int defaultOption) {
        System.err.println(title + ": " + message);
        return defaultOption;
    }

    private static String firstLines(final String text) {
        if (text == null) {
            return "";
        }
        final String[] lines = text.split("\n");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 12; i++) {
            sb.append(lines[i].trim()).append('\n');
        }
        return sb.toString().trim();
    }
}
