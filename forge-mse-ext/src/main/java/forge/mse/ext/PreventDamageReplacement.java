package forge.mse.ext;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.mse.MseReplacementPiece;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;

/**
 * The first test replacement of the extension layer: {@code MseReplacement$ prevent-damage} replaces any
 * damage event (and runs its usual {@code Execute$} ability). It exists to prove the registry and the generic
 * replacement entry work (P4-B), not as a rule of any deck.
 */
public final class PreventDamageReplacement implements MseReplacementPiece {
    @Override
    public String name() {
        return "prevent-damage";
    }

    @Override
    public Set<ReplacementType> modes() {
        return Set.of(ReplacementType.DamageDone);
    }

    @Override
    public boolean canReplace(final ReplacementEffect effect, final Map<AbilityKey, Object> runParams) {
        return true;
    }
}
