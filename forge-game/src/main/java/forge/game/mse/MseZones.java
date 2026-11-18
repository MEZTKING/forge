package forge.game.mse;

import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;

/**
 * Our own named zones (P4-C): the Herbal bag today, more later. A zone is created on first use, so a game
 * with no bag has exactly the zones Forge always had.
 */
public final class MseZones {
    private MseZones() {
    }

    /** The named zone of this player, made on first use. */
    public static PlayerZone of(final Player player, final String name) {
        return player.namedExtraZone(name);
    }

    /** The cards in that zone. */
    public static CardCollectionView cardsIn(final Player player, final String name) {
        return of(player, name).getCards();
    }

    /** A card is in this named zone? */
    public static boolean isIn(final Player player, final String name, final forge.game.card.Card card) {
        return card.getZone() == of(player, name);
    }
}
