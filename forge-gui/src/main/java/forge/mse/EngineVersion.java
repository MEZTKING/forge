package forge.mse;

/**
 * Our engine's own identity: this fork is the project's engine, not a copy that follows Forge.
 *
 * <p>Shown to the table in the bridge's {@code hello} as {@code engine} (with {@code engine_base},
 * the upstream commit this engine started from) beside Forge's own {@code forge} version. The
 * version stays additive: the protocol number doesn't change for it.</p>
 */
public final class EngineVersion {
    /** Our version. Raise it when the engine's behaviour changes; the release is the add-on's VERSION. */
    public static final String ENGINE = "MSE Engine 1.0.0-dev";

    /** The upstream commit MSE Studio's engine started from (Forge's master, 10 November 2026). */
    public static final String BASE = "09b7eda5";

    private EngineVersion() {
    }
}
