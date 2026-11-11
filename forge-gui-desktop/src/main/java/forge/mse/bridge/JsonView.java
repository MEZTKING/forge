package forge.mse.bridge;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.card.mana.ManaAtom;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableObject;
import forge.util.collect.FCollectionView;

/**
 * Forge's views (GameView, PlayerView, CardView, StackItemView, CombatView) as JSON for the
 * MSE Play table. The field names are the bridge's protocol (plans/mse-play.md in the engine
 * repository): keep them steady, add rather than rename.
 *
 * Cards the viewer may not see (another player's hand, face-down cards) are written as
 * {id, hidden: true, …} with no name or text. A viewer of null sees everything (a game
 * between AI players that is only watched).
 *
 * Call it on the game thread (or while the game thread waits for an answer): the views are
 * changed by the game thread and aren't safe to walk while it runs.
 */
public final class JsonView {
    /** The zones every player has in every `state`, by their name in the protocol. */
    static final String[] ZONE_NAMES = { "hand", "graveyard", "exile", "command", "battlefield", "sideboard" };
    private static final ZoneType[] ZONES = { ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
            ZoneType.Battlefield, ZoneType.Sideboard };
    private static final String[] MANA_NAMES = { "W", "U", "B", "R", "G", "C" };

    private final PlayerView viewer;

    public JsonView(final PlayerView viewer0) {
        viewer = viewer0;
    }

    // ------------------------------------------------------------------ the game

    public JsonObject state(final GameView game) {
        final JsonObject o = new JsonObject();
        o.addProperty("t", "state");
        o.addProperty("turn", game.getTurn());
        final PhaseType phase = game.getPhase();
        o.addProperty("phase", phase == null ? null : phase.name());
        o.add("active", id(game.getPlayerTurn()));
        PlayerView priority = null;
        final JsonArray players = new JsonArray();
        final FCollectionView<PlayerView> all = game.getPlayers();
        if (all != null) {
            for (final PlayerView p : all) {
                if (p.getHasPriority()) {
                    priority = p;
                }
                players.add(player(p, all));
            }
        }
        o.add("priority", id(priority));
        o.addProperty("mulligan", game.isMulligan());
        o.addProperty("game_over", game.isGameOver());
        o.add("players", players);
        final JsonArray stack = new JsonArray();
        final FCollectionView<StackItemView> items = game.getStack();
        if (items != null) {
            for (final StackItemView item : items) {      // the top of the stack first
                stack.add(stackItem(item));
            }
        }
        o.add("stack", stack);
        o.add("combat", combat(game.getCombat()));
        return o;
    }

    public JsonObject player(final PlayerView p, final Iterable<PlayerView> everyone) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", p.getId());
        o.addProperty("name", p.getName());
        o.addProperty("ai", p.isAI());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getCounters(CounterEnumType.POISON));
        o.add("counters", counters(p.getCounters()));
        // commander damage taken, by the commander's card id – and how often each commander was cast
        final JsonObject damage = new JsonObject();
        final JsonArray commanders = new JsonArray();
        final JsonObject cast = new JsonObject();
        for (final PlayerView other : everyone) {
            final List<CardView> theirs = other.getCommanders();
            if (theirs == null) {
                continue;
            }
            for (final CardView c : theirs) {
                final int d = p.getCommanderDamage(c);
                if (d > 0) {
                    damage.addProperty(String.valueOf(c.getId()), d);
                }
                if (other == p) {
                    commanders.add(c.getId());
                    cast.addProperty(String.valueOf(c.getId()), p.getCommanderCast(c));
                }
            }
        }
        o.add("commanders", commanders);
        o.add("commander_damage", damage);
        o.add("commander_cast", cast);
        final JsonObject mana = new JsonObject();
        for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
            mana.addProperty(MANA_NAMES[i], p.getMana(ManaAtom.MANATYPES[i]));
        }
        o.add("mana_pool", mana);
        o.addProperty("has_lost", p.getHasLost());
        o.addProperty("lands_played", p.getNumLandThisTurn());
        o.addProperty("max_hand", p.hasUnlimitedHandSize() ? -1 : p.getMaxHandSize());
        final JsonObject zones = new JsonObject();
        for (int i = 0; i < ZONES.length; i++) {
            zones.add(ZONE_NAMES[i], cards(p.getCards(ZONES[i])));
            if (i == 0) {
                zones.addProperty("library_count", p.getZoneSize(ZoneType.Library));
            }
        }
        o.add("zones", zones);
        return o;
    }

    // ------------------------------------------------------------------ cards

    public JsonArray cards(final Iterable<CardView> list) {
        final JsonArray a = new JsonArray();
        if (list != null) {
            for (final CardView c : list) {
                if (c != null) {
                    a.add(card(c));
                }
            }
        }
        return a;
    }

    /** Whether a card in this zone is in every `state` (as a card, or as hidden) – the library is only a count. */
    static boolean inState(final ZoneType zone) {
        if (zone == null) {
            return false;
        }
        for (final ZoneType z : ZONES) {
            if (z == zone) {
                return true;
            }
        }
        return false;
    }

    /**
     * `shown` in a prompt: the whole card for every card of `things` (CardViews; anything else is
     * skipped) that the table can't find face up in the `state` – cards in a library, another
     * player's hand, or in no zone. Cards the viewer may not look at come as {id, hidden: true} like
     * everywhere else, so nothing hidden is given away: a card offered from your own library is
     * one Forge lets you look at for the choice.
     */
    public JsonArray shown(final Iterable<?> things) {
        final JsonArray a = new JsonArray();
        if (things == null) {
            return a;
        }
        final Set<Integer> seen = new HashSet<>();
        for (final Object x : things) {
            if (!(x instanceof CardView c) || !seen.add(c.getId())) {
                continue;
            }
            final ZoneType zone = c.getZone();
            final boolean onTable = inState(zone)
                    && !(zone == ZoneType.Hand && viewer != null && !viewer.equals(c.getController()));
            if (!onTable) {
                a.add(card(c));
            }
        }
        return a;
    }

    public boolean maySee(final CardView c) {
        return viewer == null || c.canBeShownTo(viewer);
    }

    /** One card. Never throws: a card that can't be read comes with "error". */
    public JsonObject card(final CardView c) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        try {
            final ZoneType zone = c.getZone();
            o.addProperty("zone", zone == null ? null : zone.name());
            o.add("owner", id(c.getOwner()));
            o.add("controller", id(c.getController()));
            o.addProperty("face_down", c.isFaceDown());
            if (!maySee(c)) {
                o.addProperty("hidden", true);
                return o;
            }
            final CardStateView s = c.getCurrentState();
            o.addProperty("name", s.getName());
            o.addProperty("set", s.getSetCode());
            o.addProperty("image_key", s.getImageKey());
            o.addProperty("types", text(() -> s.getType().toString()));
            o.addProperty("mana_cost", text(() -> s.getManaCost().toString()));
            o.addProperty("colours", text(() -> s.getColors().toString()));
            o.addProperty("text", text(c::getText));
            o.addProperty("tapped", c.isTapped());
            o.addProperty("token", c.isToken());
            o.addProperty("commander", c.isCommander());
            if (s.isCreature() || s.hasPrintedPT()) {
                o.addProperty("power", s.getPower());
                o.addProperty("toughness", s.getToughness());
            }
            if (s.isPlaneswalker()) {
                o.addProperty("loyalty", s.getLoyalty());
            }
            if (s.isBattle()) {
                o.addProperty("defense", s.getDefense());
            }
            o.addProperty("damage", c.getDamage());
            o.add("counters", counters(c.getCounters()));
            final GameEntityView on = c.getEntityAttachedTo();
            if (on instanceof CardView) {
                o.addProperty("attached_to", on.getId());
            } else if (on instanceof PlayerView) {
                o.addProperty("attached_to_player", on.getId());
            }
            if (c.hasCardAttachments()) {
                o.add("attachments", ids(c.getAttachedCards()));
            }
            if (zone == ZoneType.Battlefield) {
                o.addProperty("attacking", c.isAttacking());
                o.addProperty("blocking", c.isBlocking());
                o.addProperty("sick", c.isSick());
                if (c.isPhasedOut()) {
                    o.addProperty("phased_out", true);
                }
            }
            if (c.hasAlternateState()) {       // double-faced, flip, split…: its other face
                final CardStateView alt = c.getAlternateState();
                o.addProperty("other_face", alt.getName());
                o.addProperty("face", String.valueOf(s.getState()));
            }
        } catch (final RuntimeException e) {
            o.addProperty("error", String.valueOf(e));
        }
        return o;
    }

    // ------------------------------------------------------------------ the stack and combat

    public JsonObject stackItem(final StackItemView item) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", item.getId());
        try {
            o.addProperty("key", item.getKey());
            o.addProperty("text", item.getText());
            o.add("controller", id(item.getActivatingPlayer()));
            o.addProperty("ability", item.isAbility());
            o.addProperty("trigger", item.isTrigger());
            final CardView source = item.getSourceCard();
            if (source != null) {
                o.addProperty("source", source.getId());
                o.add("card", card(source));     // a spell on the stack is in no player's zone
            }
            final JsonObject targets = new JsonObject();
            targets.add("cards", ids(item.getTargetCards()));
            targets.add("players", ids(item.getTargetPlayers()));
            targets.add("stack", ids(item.getTargetStackItems()));
            o.add("targets", targets);
        } catch (final RuntimeException e) {
            o.addProperty("error", String.valueOf(e));
        }
        return o;
    }

    public JsonObject combat(final CombatView combat) {
        if (combat == null || combat.getNumAttackers() == 0) {
            return null;
        }
        final JsonObject o = new JsonObject();
        final JsonArray attackers = new JsonArray();
        for (final CardView attacker : combat.getAttackers()) {
            final JsonObject a = new JsonObject();
            a.addProperty("card", attacker.getId());
            a.add("defender", entity(combat.getDefender(attacker)));
            final Collection<CardView> blockers = combat.getBlockers(attacker);   // null until blockers are declared
            a.addProperty("blocked", blockers != null && !blockers.isEmpty());
            a.add("blockers", ids(blockers));
            attackers.add(a);
        }
        o.add("attackers", attackers);
        return o;
    }

    // ------------------------------------------------------------------ small things

    /** {player: id} or {card: id} – players and cards number themselves separately. */
    public static JsonObject entity(final GameEntityView e) {
        if (e == null) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty(e instanceof PlayerView ? "player" : "card", e.getId());
        o.addProperty("name", e.getName());
        return o;
    }

    static JsonElement id(final GameEntityView e) {
        return e == null ? JsonNull.INSTANCE : new JsonPrimitive(e.getId());
    }

    static JsonArray ids(final Iterable<? extends TrackableObject> list) {
        final JsonArray a = new JsonArray();
        if (list != null) {
            for (final TrackableObject x : list) {
                if (x != null) {
                    a.add(x.getId());
                }
            }
        }
        return a;
    }

    static JsonObject counters(final Multiset<CounterType> counters) {
        final JsonObject o = new JsonObject();
        if (counters != null) {
            for (final Multiset.Entry<CounterType> e : counters.entrySet()) {
                if (e.getCount() > 0) {
                    o.addProperty(e.getElement().getName(), e.getCount());
                }
            }
        }
        return o;
    }

    private static String text(final Supplier<String> get) {
        try {
            return get.get();
        } catch (final RuntimeException e) {
            return null;
        }
    }
}
