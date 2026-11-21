package forge.mse.ext;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Full-House set completion on the extension layer: hears one of the two parts arriving or changing
 * controller, and fires when their controller has both parts on the battlefield. The card's bracketed
 * effect itself stays an ordinary script sub-ability ({@code Execute$}).
 */
public final class FullHouseTrigger implements forge.game.mse.MseTriggerPiece {
    @Override
    public String name() {
        return "full-house";
    }

    @Override
    public Set<TriggerType> modes() {
        return Set.of(TriggerType.ChangesZone, TriggerType.ChangesController);
    }

    @Override
    public boolean performTest(final Trigger trigger, final Map<AbilityKey, Object> runParams) {
        final Card host = trigger.getHostCard();
        if (host == null || !host.isInZone(ZoneType.Battlefield)) {
            return false;
        }
        final Player controller = host.getController();
        final String partner = trigger.getParam("FHPartner");
        if (controller == null || partner == null || partner.isEmpty()) {
            return false;
        }
        Card other = null;
        for (final Card c : host.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(partner) && c.getController() == controller) {
                other = c;
                break;
            }
        }
        if (other == null) {
            return false;
        }
        final Object changed = runParams.get(AbilityKey.Card);
        return changed == host || changed == other;
    }

    @Override
    public void setTriggeringObjects(final Trigger trigger, final SpellAbility sa,
                                     final Map<AbilityKey, Object> runParams) {
        sa.setTriggeringObjectsFrom(runParams, AbilityKey.Card);
    }
}
