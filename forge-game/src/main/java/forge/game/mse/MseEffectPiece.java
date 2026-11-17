package forge.game.mse;

import forge.game.spellability.SpellAbility;

/**
 * One effect a card script can name as {@code A:AB$ Mse | Mse$ <name>} (P4-B).
 *
 * <p>Pieces live in <code>forge-mse-ext</code>, not in forge-game; the game only knows this interface.</p>
 */
public interface MseEffectPiece {
    /** The name a script writes (lower-case with hyphens, stable). */
    String name();

    /** Do what the effect does; the host card, params and targets are on {@code sa} as for any effect. */
    void resolve(SpellAbility sa);
}
