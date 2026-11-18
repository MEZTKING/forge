package forge.game.mse;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import forge.game.trigger.TriggerType;

/**
 * The extension layer's registry (P4-B): effects ({@link MseEffectPiece}) and triggers
 * ({@link MseTriggerPiece}), filled once from {@link ServiceLoader}. Nothing registered = nothing changes.
 */
public final class MseRegistry {
    private static final Map<String, MseEffectPiece> EFFECTS = new ConcurrentHashMap<>();
    private static final Map<String, MseTriggerPiece> TRIGGERS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private MseRegistry() {
    }

    /** Load every piece the classpath offers (idempotent). */
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
            for (final MseTriggerPiece piece : ServiceLoader.load(MseTriggerPiece.class)) {
                TRIGGERS.put(piece.name().toLowerCase(java.util.Locale.ROOT), piece);
            }
            loaded = true;
        }
    }

    public static void register(final MseEffectPiece piece) {
        EFFECTS.put(piece.name().toLowerCase(java.util.Locale.ROOT), piece);
        loaded = true;
    }

    public static void register(final MseTriggerPiece piece) {
        TRIGGERS.put(piece.name().toLowerCase(java.util.Locale.ROOT), piece);
        loaded = true;
    }

    /** The effect a script names, or null. */
    public static MseEffectPiece effect(final String name) {
        load();
        return name == null ? null : EFFECTS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** The trigger a script names, or null. */
    public static MseTriggerPiece trigger(final String name) {
        load();
        return name == null ? null : TRIGGERS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Does the trigger with this name want to hear this event mode? */
    public static boolean wants(final String name, final TriggerType mode) {
        final MseTriggerPiece piece = trigger(name);
        return piece != null && (piece.modes().isEmpty() || piece.modes().contains(mode));
    }

    public static int size() {
        load();
        return EFFECTS.size() + TRIGGERS.size();
    }
}
