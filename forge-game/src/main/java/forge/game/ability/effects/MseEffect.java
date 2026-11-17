package forge.game.ability.effects;

import forge.game.ability.SpellAbilityEffect;
import forge.game.mse.MseEffectPiece;
import forge.game.mse.MseRegistry;
import forge.game.spellability.SpellAbility;

/**
 * The generic effect entry of the extension layer (P4-B): {@code A:AB$ Mse | Mse$ <name>} runs the piece
 * registered under that name (in <code>forge-mse-ext</code>). An unknown name stops with a clear message –
 * a card asking for a piece nobody registered is a setup error, never silently ignored.
 */
public class MseEffect extends SpellAbilityEffect {
    @Override
    public void resolve(final SpellAbility sa) {
        final String name = sa.getParam("Mse");
        final MseEffectPiece piece = MseRegistry.effect(name);
        if (piece == null) {
            throw new RuntimeException("No extension-layer effect is registered as \"" + name
                    + "\" (the card script asks for it)");
        }
        piece.resolve(sa);
    }

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return sa.getHostCard() == null ? "Mse" : sa.getHostCard().getName();
    }
}
