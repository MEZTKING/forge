package forge.mse.ext;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.mse.MseReplacementPiece;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;

/**
 * Mithridatism, on the extension layer: the card's owner loses for poison only when the limit says so
 * ({@code Limit$ 30}), and never without a limit (Eminent Mithridatism). It replaces the game loss at the
 * CantHappen layer, the same way the converter's old {@code Event$ GameLoss} line did.
 */
public final class MithridatismReplacement implements MseReplacementPiece {
    @Override
    public String name() {
        return "mithridatism";
    }

    @Override
    public Set<ReplacementType> modes() {
        return Set.of(ReplacementType.GameLoss);
    }

    @Override
    public boolean canReplace(final ReplacementEffect effect, final Map<AbilityKey, Object> runParams) {
        final Object affected = runParams.get(AbilityKey.Affected);
        if (!(affected instanceof Player player) || player != effect.getHostCard().getOwner()) {
            return false;                       // only her owner's loss, not someone who stole her
        }
        final Object reason = runParams.get(AbilityKey.LoseReason);
        if (reason != GameLossReason.Poisoned) {
            return false;
        }
        if (!effect.hasParam("Limit")) {
            return true;                        // Eminent Mithridatism: no poison limit
        }
        return player.getPoisonCounters() < Integer.parseInt(effect.getParam("Limit"));
    }
}
