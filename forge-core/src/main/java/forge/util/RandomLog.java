package forge.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The random half of the decision log (P3-B): every draw {@link MyRandom} makes is written in order, and
 * a recorded game can be played back with the same draws. The AI's own decisions come next; this is the
 * part that removes shuffles, coin flips and "at random" from a replay.
 *
 * <p>One line per draw: the integer the random call returned. Off (no file): no change at all.</p>
 */
public final class RandomLog {
    private static BufferedWriter out;
    private static Iterator<Integer> replay;
    private static int replayed;
    private static int recorded;

    private RandomLog() {
    }

    /** Start recording to (or replaying from) one file; neither leaves the random draws alone. */
    public static synchronized void start(final String outPath, final String inPath) {
        close();
        try {
            if (outPath != null) {
                final Path path = Path.of(outPath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            } else if (inPath != null) {
                final List<Integer> values = new ArrayList<>();
                for (final String line : Files.readAllLines(Path.of(inPath), StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        values.add(Integer.parseInt(line.trim()));
                    }
                }
                replay = values.iterator();
            }
        } catch (final IOException | NumberFormatException e) {
            throw new IllegalStateException("The decision log can't be used: " + e.getMessage(), e);
        }
    }

    /** The draw to use: the recorded one in a replay, otherwise this one (and it is written down). */
    public static synchronized int take(final int value) {
        if (replay != null) {
            if (!replay.hasNext()) {
                throw new IllegalStateException("The decision log ran out after " + replayed + " random draws");
            }
            replayed++;
            return replay.next();
        }
        if (out != null) {
            try {
                out.write(Integer.toString(value));
                out.write('\n');
                recorded++;
            } catch (final IOException e) {
                throw new IllegalStateException("The decision log can't be written: " + e.getMessage(), e);
            }
        }
        return value;
    }

    public static synchronized void close() {
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (final IOException ignored) {
                // the game is over; nothing useful to do about a flush that failed
            }
            out = null;
        }
        replay = null;
    }

    public static synchronized int recordedCount() {
        return recorded;
    }
}
