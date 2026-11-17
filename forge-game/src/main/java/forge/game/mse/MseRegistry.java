package forge.game.mse;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The effects the extension layer offers (P4-B): a name from a script to a piece. Filled once from
 * {@link ServiceLoader}, so a plug-in on the classpath is found without forge-game knowing it.
 */
public final class MseRegistry {
    private static final Map<String, MseEffectPiece> EFFECTS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private MseRegistry() {
    }

    /** Load every {@link MseEffectPiece} the classpath offers (idempotent). */
    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (MseRegistry.class) {
            if (loaded) {
                return;
            }
            for (final MseEffectPiece piece : ServiceLoader.load(MseEffectPiece.class)) {
                EFFECTS.put(piece.name().toLowerCase(java.util.Locale.ROOT), piece);
            }
            loaded = true;
        }
    }

    /** Register a piece directly (tests, built-ins). */
    public static void register(final MseEffectPiece piece) {
        EFFECTS.put(piece.name().toLowerCase(java.util.Locale.ROOT), piece);
        loaded = true;
    }

    /** The piece a script names, or null when nothing is registered under it. */
    public static MseEffectPiece effect(final String name) {
        load();
        return name == null ? null : EFFECTS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static int size() {
        load();
        return EFFECTS.size();
    }
}
