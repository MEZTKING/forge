package forge.mse.ext;

import forge.game.mse.MseEffectPiece;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * The first test piece of the extension layer: {@code Mse$ draw-a-card} draws one card for the player the
 * ability is for. It exists to prove the registry and the generic effect entry work (P4-B), not as a rule of
 * any deck.
 */
public final class DrawCardEffect implements MseEffectPiece {
    @Override
    public String name() {
        return "draw-a-card";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Player player = sa.getActivatingPlayer();
        if (player != null) {
            player.drawCards(1);
        }
    }
}
