package forge.game.trigger;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.mse.MseRegistry;
import forge.game.mse.MseTriggerPiece;
import forge.game.spellability.SpellAbility;

/**
 * The generic trigger entry of the extension layer (P4-B): {@code T:Mode$ Mse | MseTrigger$ <name>}. The
 * piece (in <code>forge-mse-ext</code>) says which Forge event modes it hears and what it does; an unknown
 * name is a setup error when the card is built, never silently ignored.
 */
public class TriggerMse extends Trigger {
    private static final long serialVersionUID = 1L;

    private final MseTriggerPiece piece;

    public TriggerMse(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
        final String name = params.get("MseTrigger");
        this.piece = MseRegistry.trigger(name);
        if (this.piece == null) {
            throw new RuntimeException("No extension-layer trigger is registered as \"" + name
                    + "\" (the card script asks for it)");
        }
    }

    /** Does the piece want to hear this Forge event mode? */
    public boolean watches(final TriggerType mode) {
        return piece.modes().isEmpty() || piece.modes().contains(mode);
    }

    @Override
    public boolean performTest(final Map<AbilityKey, Object> runParams) {
        return piece.performTest(this, runParams);
    }

    @Override
    public void setTriggeringObjects(final SpellAbility sa, final Map<AbilityKey, Object> runParams) {
        piece.setTriggeringObjects(this, sa, runParams);
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return piece.getImportantStackObjects(this, sa);
    }
}
