package forge.mse.ext;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.mse.MseTriggerPiece;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * The first test trigger of the extension layer: {@code MseTrigger$ tap-draw} hears every permanent being
 * tapped and draws a card for the trigger's controller. It exists to prove the registry and the generic
 * trigger entry work (P4-B), not as a rule of any deck.
 */
public final class TapDrawTrigger implements MseTriggerPiece {
    @Override
    public String name() {
        return "tap-draw";
    }

    @Override
    public Set<TriggerType> modes() {
        return Set.of(TriggerType.Taps);
    }

    @Override
    public boolean performTest(final Trigger trigger, final Map<AbilityKey, Object> runParams) {
        return true;
    }

    @Override
    public void setTriggeringObjects(final Trigger trigger, final SpellAbility sa,
                                     final Map<AbilityKey, Object> runParams) {
        sa.setTriggeringObjectsFrom(runParams, AbilityKey.Card);
    }
}
