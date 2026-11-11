package forge.mse.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import forge.GuiDesktop;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;
import forge.util.FSerializableFunction;

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
        final JsonGuiGame g = gui;
        if (g != null && g.isInteractive()) {
            return g.showOptionDialog(message, title, icon, options, defaultOption);
        }
        System.err.println(title + ": " + message);
        return defaultOption;
    }

    // Forge's dialogs outside a game window (SGuiChoose, SOptionPane): to the table when a person plays

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput,
            final List<String> inputOptions, final boolean isNumeric) {
        final JsonGuiGame g = gui;
        if (g != null && g.isInteractive()) {
            return g.showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric);
        }
        System.err.println(title + ": " + message);
        return initialInput;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices,
            final Collection<T> selected, final FSerializableFunction<T, String> display) {
        final JsonGuiGame g = gui;
        final List<T> all = choices == null ? new ArrayList<>() : new ArrayList<>(choices);
        if (g != null) {
            return g.getChoices(message, min, max, all, selected == null ? null : new ArrayList<>(selected), display);
        }
        return new ArrayList<>(all.subList(0, Math.max(0, Math.min(min, all.size()))));
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
        final JsonGuiGame g = gui;
        if (g != null) {
            return g.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, null, false,
                    false).ordered();
        }
        return new ArrayList<>(sourceChoices);
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
