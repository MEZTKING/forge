package forge.game.mse;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * One trigger a card script can name as {@code T:Mode$ Mse | MseTrigger$ <name>} (P4-B).
 *
 * <p>The piece says which Forge event modes it wants to hear (empty: all of them); Forge's trigger dispatch
 * then tests it like any other trigger.</p>
 */
public interface MseTriggerPiece {
    /** The name a script writes. */
    String name();

    /** The events to hear (empty: every event). */
    Set<TriggerType> modes();

    /** Would this event fire the trigger? */
    boolean performTest(Trigger trigger, Map<AbilityKey, Object> runParams);

    /** What the trigger's effect can refer to (the same contract as Forge's own triggers). */
    void setTriggeringObjects(Trigger trigger, SpellAbility sa, Map<AbilityKey, Object> runParams);

    default String getImportantStackObjects(final Trigger trigger, final SpellAbility sa) {
        return "";
    }
}
