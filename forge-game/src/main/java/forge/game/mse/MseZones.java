package forge.game.mse;

import java.util.ArrayList;
import java.util.List;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

/**
 * Our own named zones (P4-C): the Herbal bag today, more later. A zone is created on first use, so a game
 * with no bag has exactly the zones Forge always had.
 */
public final class MseZones {
    private MseZones() {
    }

    /** The named zone of this player, made on first use. Asking for the bag fills it (once) from the
     *  deck's Herbal sideboard cards, so a game that never asks for the bag is unchanged. */
    public static PlayerZone of(final Player player, final String name) {
        final PlayerZone zone = player.namedExtraZone(name);
        if ("bag".equalsIgnoreCase(name) && !player.isBagFilled()) {
            player.markBagFilled();
            fillBag(player, zone);
        }
        return zone;
    }

    private static void fillBag(final Player player, final PlayerZone bag) {
        final List<Card> herbal = new ArrayList<>();
        for (final Card card : player.getCardsIn(ZoneType.Sideboard)) {
            if (card.getType().hasSubtype("Herbal")) {
                herbal.add(card);
            }
        }
        for (final Card card : herbal) {
            player.getGame().getAction().moveTo(bag, card, null, AbilityKey.newMap());
        }
    }

    /** The cards in that zone. */
    public static CardCollectionView cardsIn(final Player player, final String name) {
        return of(player, name).getCards();
    }

    /** A card is in this named zone? */
    public static boolean isIn(final Player player, final String name, final forge.game.card.Card card) {
        return card.getZone() == of(player, name);
    }

    /** Move a card into the named zone (through the usual action, so triggers and replacements see it). */
    public static Card moveTo(final Player player, final String name, final Card card, final SpellAbility cause) {
        return player.getGame().getAction().moveTo(of(player, name), card, cause, AbilityKey.newMap());
    }

    /** At game start, the deck's Herbal sideboard cards go into the bag; nothing else moves. */
    public static void setupBag(final Game game) {
        for (final Player player : game.getPlayers()) {
            final List<Card> herbal = new ArrayList<>();
            for (final Card card : player.getCardsIn(ZoneType.Sideboard)) {
                if (card.getType().hasSubtype("Herbal")) {
                    herbal.add(card);
                }
            }
            for (final Card card : herbal) {
                moveTo(player, "bag", card, null);
            }
        }
    }
}
