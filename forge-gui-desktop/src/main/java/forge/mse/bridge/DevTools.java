package forge.mse.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.StaticData;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEntity;
import forge.game.GameState;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.player.PlayerControllerHuman;

/**
 * MSE Play's testing tools on the bridge's side (plans/mse-play.md, "Testing"): the `dev` messages,
 * the start of a game that isn't a plain one (poison, counters, a starting board), scenarios
 * (`export_state` and --starting-board) and the `checkpoint` messages.
 *
 * Everything that changes the game runs on Forge's game thread, at the one moment the game is at
 * rest: when a player is about to get priority ({@link #atPriority}, called for Forge's
 * GameEventPlayerPriority). A `dev` message is put in a queue and done there. When the person at
 * the table is being asked what to do (Forge's InputPassPriority), that prompt is ended without an
 * action first ({@link JsonGuiGame#kick}, the way Forge's own undo does it), so the game comes
 * round to that moment at once; in any other case the message waits for the next one (`dev_result`
 * with `stage: "queued"` says so). After a change the player who would act is asked again, so
 * Forge checks its state-based actions and puts triggers on the stack before anyone goes on.
 */
final class DevTools {
    /** Every `op` a `dev` message may carry (told in hello as `dev_ops`). */
    static final String[] OPS = { "set_life", "add_life", "set_poison", "add_counters", "remove_counters", "set_counters",
        "add_card", "remove_card", "move_card", "draw", "tap", "untap", "tap_all", "untap_all", "shuffle", "reorder_top",
        "peek", "set_pt", "add_keyword", "remove_keyword", "add_type", "extra_turn", "skip_to", "stop_skip", "win", "lose",
        "mana", "free_mana", "infinite_mana", "no_summoning_sickness", "hold_priority", "pause_before_ai_spell",
        "unlimited_lands", "show_opponent_hand", "ai_mode", "ai_log", "set_rule", "export_state" };
    private static final String COLOURS = "WUBRGC";
    private static final String[] BOARD_ZONES = { "battlefield", "hand", "graveyard", "exile", "command", "library_top",
        "library_bottom", "sideboard" };

    private final JsonGuiGame gui;
    private final BridgeWire wire;
    private final BridgeOptions opt;
    private volatile Game game;

    private final ConcurrentLinkedQueue<JsonObject> queue = new ConcurrentLinkedQueue<>();
    private long tick = 0;                      // priority moments so far (game thread)
    private volatile boolean recheck = false;   // a change was made: whoever acts next is asked again first
    private volatile boolean running = false;

    // switches that stay on
    private final Map<Integer, String> endlessMana = new ConcurrentHashMap<>();   // player id → colours
    private final Map<Integer, Boolean> noSickness = new ConcurrentHashMap<>();   // player id (-1: everyone)
    private volatile boolean holdPriority = false;
    private volatile boolean pauseBeforeAi = false;
    private volatile Skip skip = null;
    private Card manaSource = null;

    // checkpoints
    private int checkpoints = 0;
    private int actions = 0;
    // catching up (a watched game with --replay-until)
    private int untilTurn = -1;
    private PhaseType untilPhase = null;

    /** A `skip_to` that is under way. */
    private record Skip(JsonObject message, boolean endTurns, int player, PhaseType phase, int fromTurn,
            PhaseType fromPhase) { }

    DevTools(final JsonGuiGame gui0, final BridgeWire wire0, final BridgeOptions opt0) {
        gui = gui0;
        wire = wire0;
        opt = opt0;
        if (opt.replayUntil != null) {
            final String[] parts = opt.replayUntil.split(":", 2);
            untilTurn = Integer.parseInt(parts[0].trim());
            untilPhase = parts.length > 1 ? PhaseType.valueOf(parts[1].trim().toUpperCase()) : null;
        }
        if (opt.devOps != null) {       // dev messages kept from an earlier game (each with `at`), one a line
            try {
                for (final String line : Files.readAllLines(Paths.get(opt.devOps), StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        final JsonObject m = JsonParser.parseString(line).getAsJsonObject();
                        queue.add(m.has("send") && m.get("send").isJsonObject() ? m.getAsJsonObject("send") : m);
                    }
                }
            } catch (final IOException | RuntimeException e) {
                opt.problems.add("The dev actions file couldn't be read: " + e.getMessage());
            }
        }
    }

    void setGame(final Game g) {
        game = g;
        tick = 0;
        checkpoints = 0;
        actions = 0;
    }

    boolean holdsPriority() {
        return holdPriority;
    }

    /** True while the person's priority is passed for them (a `skip_to` is under way). */
    boolean isSkipping() {
        return skip != null;
    }

    /** A change was made since anyone was last asked what to do: ask again (once), so Forge checks the game first. */
    boolean takeRecheck() {
        if (recheck) {
            recheck = false;
            return true;
        }
        return false;
    }

    /**
     * Whether a testing action waits to be done at the next rest (one without `at`): one that came
     * while a priority prompt was being asked again – after Forge's last rest, before the new prompt
     * showed – would otherwise wait for the person's next click.
     */
    boolean hasWaiting() {
        for (final JsonObject m : queue) {
            if (!m.has("at")) {
                return true;
            }
        }
        return false;
    }

    boolean pausesBeforeAi() {
        return pauseBeforeAi;
    }

    /** Whether the bridge still waits to hand a caught-up watched game over (--replay-until). */
    boolean catchingUp() {
        return untilTurn >= 0;
    }

    // ================================================================== from the table

    /** `dev` {op, …} – on the reader's thread. Returns true when the game should be brought to rest for it now. */
    boolean onDev(final JsonObject m) {
        final String op = m.has("op") && m.get("op").isJsonPrimitive() ? m.get("op").getAsString() : "";
        if (!opt.testing) {
            result(m, false, "done", "Not a testing game: the testing tools are off.");
            return false;
        }
        if (!Arrays.asList(OPS).contains(op)) {
            result(m, false, "done", "The bridge doesn't know the testing action \"" + op + "\".");
            return false;
        }
        if (game == null || game.isGameOver()) {
            result(m, false, "done", "No game is running.");
            return false;
        }
        queue.add(m);
        return !m.has("at");
    }

    /** Said when a message has to wait for the game to come to rest. */
    void saidQueued(final JsonObject m) {
        result(m, true, "queued", "This will be done as soon as the game is at rest (when a player next gets priority).");
    }

    private void result(final JsonObject m, final boolean ok, final String stage, final String text) {
        final JsonObject o = new JsonObject();
        o.addProperty("t", "dev_result");
        o.add("op", m.get("op"));
        if (m.has("id")) {
            o.add("id", m.get("id"));
        }
        o.addProperty("ok", ok);
        o.addProperty("stage", stage);
        o.addProperty("text", text);
        final Game g = game;
        if (g != null && "done".equals(stage) && ok) {
            o.addProperty("tick", tick);
            o.addProperty("turn", g.getPhaseHandler().getTurn());
            final PhaseType phase = g.getPhaseHandler().getPhase();
            o.addProperty("phase", phase == null ? null : phase.name());
        }
        if (m.has("kicked")) {
            o.add("kicked", m.get("kicked"));
        }
        if (m.has("cards")) {
            o.add("cards", m.get("cards"));
        }
        wire.send(o);
    }

    // ================================================================== on the game thread

    /** Forge is about to give a player priority: the moment the game is at rest. */
    void atPriority() {
        final Game g = game;
        if (g == null || g.isGameOver()) {
            return;
        }
        tick++;
        recheck = false;        // Forge has just checked the game
        final PhaseHandler ph = g.getPhaseHandler();
        if (opt.turnLimit > 0 && ph.getTurn() > opt.turnLimit) {
            log("The turn limit (" + opt.turnLimit + ") is reached: the game ends in a draw.");
            for (final Player p : g.getPlayers()) {
                p.intentionalDraw();        // nobody won: without this Forge names the first player the winner
            }
            g.setGameOver(GameEndReason.Draw);
            releaseInputs(g);
            return;
        }
        if (untilTurn >= 0 && (ph.getTurn() > untilTurn || ph.getTurn() == untilTurn
                && (untilPhase == null || ph.getPhase() == untilPhase || untilPhase.isBefore(ph.getPhase())))) {
            untilTurn = -1;
            gui.caughtUp();
        }
        boolean changed = false;
        running = true;
        try {
            for (final JsonObject m : new ArrayList<>(queue)) {
                if (!due(m, ph)) {
                    continue;
                }
                queue.remove(m);
                changed |= run(g, m);
                if (g.isGameOver()) {
                    break;
                }
            }
            changed |= switches(g);
            changed |= stepSkip(g);
        } finally {
            running = false;
        }
        if (g.isGameOver()) {
            releaseInputs(g);
        } else if (changed) {
            recheck = true;
            g.getUndoManager().barrier("Testing tools");
            gui.devChanged();
            checkpoint(g, "dev");
        }
    }

    private boolean due(final JsonObject m, final PhaseHandler ph) {
        if (!m.has("at") || !m.get("at").isJsonObject()) {
            return true;
        }
        final JsonObject at = m.getAsJsonObject("at");
        if (at.has("tick")) {
            return tick >= at.get("tick").getAsLong();
        }
        final int turn = at.has("turn") ? at.get("turn").getAsInt() : 0;
        if (ph.getTurn() != turn) {
            return ph.getTurn() > turn;
        }
        if (!at.has("phase") || at.get("phase").isJsonNull()) {
            return true;
        }
        final PhaseType phase = PhaseType.valueOf(at.get("phase").getAsString().toUpperCase());
        return ph.getPhase() == phase || phase.isBefore(ph.getPhase());
    }

    private static void releaseInputs(final Game g) {
        for (final Player p : g.getRegisteredPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch) {
                pch.getInputQueue().onGameOver(true);
            }
        }
    }

    private void log(final String text) {
        final JsonObject o = new JsonObject();
        o.addProperty("t", "log");
        o.addProperty("kind", "DEV");
        o.addProperty("text", text);
        wire.send(o);
    }

    /** One `dev` message. Returns true when it changed the game. Never throws: what went wrong is the answer. */
    private boolean run(final Game g, final JsonObject m) {
        final String op = m.get("op").getAsString();
        try {
            final String text = apply(g, op, m);
            if (!"skip_to".equals(op)) {
                result(m, true, "done", text);
            }
            log(text);
            return !"peek".equals(op) && !"export_state".equals(op);
        } catch (final IllegalArgumentException e) {
            result(m, false, "done", e.getMessage());
        } catch (final RuntimeException e) {
            e.printStackTrace();
            result(m, false, "done", "Forge couldn't do that: " + e);
        }
        return false;
    }

    private String apply(final Game g, final String op, final JsonObject m) {
        switch (op) {
            case "set_life": {
                final Player p = player(g, m);
                p.setLife(need(m, "value"), null);
                return p + ": " + p.getLife() + " life.";
            }
            case "add_life": {
                final Player p = player(g, m);
                p.setLife(p.getLife() + need(m, "n"), null);
                return p + ": " + p.getLife() + " life.";
            }
            case "set_poison": {
                final Player p = player(g, m);
                p.setPoisonCounters(Math.max(0, need(m, "value")), p);
                return p + ": " + p.getPoisonCounters() + " poison counters.";
            }
            case "add_counters":
            case "remove_counters":
            case "set_counters":
                return counters(g, op, m);
            case "add_card":
                return addCard(g, m);
            case "remove_card": {
                final Card c = card(g, m);
                g.getAction().ceaseToExist(c, true);
                return c + " is removed from the game.";
            }
            case "move_card": {
                final Card c = card(g, m);
                final Player to = m.has("player") ? player(g, m) : c.getOwner();
                place(g, c, to, text(m, "zone", "hand"), m, false);
                return c + " is moved to " + to + "'s " + text(m, "zone", "hand") + ".";
            }
            case "draw": {
                final Player p = player(g, m);
                final int n = Math.max(1, number(m, "n", 1));
                p.drawCards(n);
                return p + ": " + n + (n == 1 ? " card drawn." : " cards drawn.");
            }
            case "tap":
            case "untap": {
                final Card c = card(g, m);
                if (op.equals("tap")) {
                    c.tap(true, null, null);
                } else {
                    c.untap();
                }
                return c + " is " + (op.equals("tap") ? "tapped." : "untapped.");
            }
            case "tap_all":
            case "untap_all": {
                final Player p = m.has("player") ? player(g, m) : null;
                int n = 0;
                for (final Card c : p == null ? g.getCardsIn(ZoneType.Battlefield) : p.getCardsIn(ZoneType.Battlefield)) {
                    if (op.equals("tap_all") ? !c.isTapped() && c.tap(true, null, null) : c.isTapped() && c.untap()) {
                        n++;
                    }
                }
                return n + " of " + (p == null ? "everybody" : p) + "'s permanents " + (op.equals("tap_all") ? "tapped." : "untapped.");
            }
            case "shuffle": {
                final Player p = player(g, m);
                p.shuffle(null);
                return p + "'s library is shuffled.";
            }
            case "reorder_top":
                return reorderTop(g, m);
            case "peek":
                return peek(g, m);
            case "set_pt": {
                final Card c = card(g, m);
                final long ts = g.getNextTimestamp();
                c.addNewPT(m.has("power") ? m.get("power").getAsInt() : null,
                        m.has("toughness") ? m.get("toughness").getAsInt() : null, ts, 0);
                if (flag(m, "until_end_of_turn", false)) {
                    g.getEndOfTurn().addUntil(() -> c.removeNewPT(ts, 0));
                }
                return c + " is " + c.getNetPower() + "/" + c.getNetToughness() + ".";
            }
            case "add_keyword":
            case "remove_keyword": {
                final Card c = card(g, m);
                final List<String> words = List.of(needText(m, "keyword"));
                final long ts = g.getNextTimestamp();
                final boolean add = op.equals("add_keyword");
                c.addChangedCardKeywords(add ? words : null, add ? null : words, false, ts, null);
                if (flag(m, "until_end_of_turn", false)) {
                    g.getEndOfTurn().addUntil(() -> c.removeChangedCardKeywords(ts, 0));
                }
                return c + (add ? " has " : " loses ") + words.get(0) + ".";
            }
            case "add_type": {
                final Card c = card(g, m);
                final long ts = g.getNextTimestamp();
                c.addChangedCardTypes(CardType.parse(needText(m, "type"), true), null, false,
                        EnumSet.noneOf(forge.card.RemoveType.class), ts, 0, true, false);
                return c + " is also " + needText(m, "type") + ".";
            }
            case "extra_turn": {
                final Player p = player(g, m);
                g.getPhaseHandler().addExtraTurn(p);
                return p + ": an extra turn after this one.";
            }
            case "skip_to":
                return skipTo(g, m);
            case "stop_skip":
                skip = null;
                return "No longer skipping.";
            case "win": {
                final Player p = player(g, m);
                for (final Player other : g.getPlayers()) {
                    if (other != p && !other.hasLost()) {
                        other.loseConditionMet(GameLossReason.SpellEffect, "Testing tools");
                    }
                }
                return "Winner: " + p + ".";
            }
            case "lose": {
                final Player p = player(g, m);
                p.loseConditionMet(GameLossReason.SpellEffect, "Testing tools");
                return p + ": out of the game.";
            }
            case "mana": {
                final Player p = player(g, m);
                final String colours = colours(text(m, "colours", text(m, "colors", COLOURS)));
                addMana(g, p, colours, Math.max(1, number(m, "n", 1)));
                return p + ": mana added (" + colours + ").";
            }
            case "free_mana":
            case "infinite_mana": {
                final Player p = player(g, m);
                final String colours = colours(text(m, "colours", text(m, "colors", COLOURS)));
                if (flag(m, "on", true)) {
                    endlessMana.put(p.getId(), colours);
                    return p + ": all the " + colours + " mana wanted, from now on.";
                }
                endlessMana.remove(p.getId());
                p.getManaPool().resetPool();
                return p + ": no more free mana.";
            }
            case "no_summoning_sickness": {
                final int who = m.has("player") ? player(g, m).getId() : -1;
                if (flag(m, "on", true)) {
                    noSickness.put(who, true);
                    return "Creatures can attack and tap the turn they come in.";
                }
                noSickness.clear();
                return "Summoning sickness is back.";
            }
            case "hold_priority":
                holdPriority = flag(m, "on", true);
                return holdPriority ? "You are asked at every step." : "You are asked at your stops only.";
            case "pause_before_ai_spell":
                pauseBeforeAi = flag(m, "on", true);
                return pauseBeforeAi ? "The game pauses before an opponent casts a spell." : "No pause before an opponent's spells.";
            case "unlimited_lands": {
                final PlayerControllerHuman h = gui.human();
                if (h == null) {
                    throw new IllegalArgumentException("Nobody is playing: only a person can play extra lands.");
                }
                h.cheat().setCanPlayUnlimitedLands(flag(m, "on", true));
                return flag(m, "on", true) ? "You can play any number of lands a turn." : "One land a turn again.";
            }
            case "show_opponent_hand":
                gui.setShowAll(flag(m, "on", true));
                return flag(m, "on", true) ? "Every hand is shown." : "The other hands are hidden again.";
            case "ai_mode": {
                final Player p = player(g, m);
                if (!(p.getLobbyPlayer() instanceof BridgeAi ai)) {
                    throw new IllegalArgumentException(p + " isn't played by the AI.");
                }
                ai.setMode(BridgeAi.Mode.parse(text(m, "mode", "normal")));
                return p + "'s AI: " + ai.getMode().name().toLowerCase().replace('_', ' ') + ".";
            }
            case "ai_log":
                gui.setAiLog(flag(m, "on", true));
                return flag(m, "on", true) ? "The AI's decisions are in the log." : "The AI's decisions are no longer in the log.";
            case "set_rule":
                return setRule(g, m);
            case "export_state":
                export(g, m);
                return "The position is saved as a scenario.";
            default:
                throw new IllegalArgumentException("The bridge doesn't know the testing action \"" + op + "\".");
        }
    }

    // ------------------------------------------------------------------ the actions

    private String counters(final Game g, final String op, final JsonObject m) {
        final GameEntity on = m.has("card") ? card(g, m) : player(g, m);
        final CounterType type = counterType(needText(m, "type"));
        final int now = on.getCounters(type);
        final int want;
        if (op.equals("set_counters")) {
            want = need(m, "value");
        } else {
            want = now + (op.equals("remove_counters") ? -1 : 1) * number(m, "n", 1);
        }
        final int to = Math.max(0, want);
        if (on instanceof Player p) {
            p.setCounters(type, to, p, true);
        } else if (on instanceof Card c) {
            if (to > now) {
                c.addCounterInternal(type, to - now, c.getController(), false, null, null);
            } else if (to < now) {
                c.subtractCounter(type, now - to, null);
            }
        }
        return on + ": " + on.getCounters(type) + " " + type.getName() + " counters.";
    }

    static CounterType counterType(final String name) {
        final String n = name.trim();
        if (n.equals("+1/+1")) {
            return CounterEnumType.P1P1;
        } else if (n.equals("-1/-1")) {
            return CounterEnumType.M1M1;
        }
        for (final CounterEnumType t : CounterEnumType.values()) {
            if (t.name().equalsIgnoreCase(n) || t.getName().equalsIgnoreCase(n)) {
                return t;
            }
        }
        return CounterType.getType(n);
    }

    private String addCard(final Game g, final JsonObject m) {
        final Player p = player(g, m);
        final String name = needText(m, "name");
        final String zone = text(m, "zone", "hand");
        final int count = Math.max(1, number(m, "count", 1));
        final String source = text(m, "source", "auto");        // auto: from the library when it's there, else a new card
        int made = 0;
        int taken = 0;
        for (int i = 0; i < count; i++) {
            Card c = source.equals("new") || zone.startsWith("library") ? null : byName(p.getCardsIn(ZoneType.Library), name);
            if (c != null) {
                taken++;
            } else if (source.equals("library")) {
                throw new IllegalArgumentException(i == 0 ? "No card called " + name + " is in " + p + "'s library."
                        : "Only " + i + " of " + name + " were in " + p + "'s library.");
            } else {
                c = newCard(g, p, name);
                made++;
            }
            place(g, c, p, zone, m, true);
        }
        return count + " × " + name + " → " + p + "'s " + zone
                + (made == 0 ? " (from the library)." : taken == 0 ? " (new)." : " (" + taken + " from the library, " + made + " new).");
    }

    private static Card byName(final Iterable<Card> cards, final String name) {
        for (final Card c : cards) {
            if (c.getName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    static Card newCard(final Game g, final Player owner, final String name) {
        PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
        if (pc == null) {
            pc = StaticData.instance().getCommonCards().getUniqueByName(name);
        }
        if (pc == null && StaticData.instance().getVariantCards() != null) {
            pc = StaticData.instance().getVariantCards().getCard(name);
        }
        if (pc == null) {
            throw new IllegalArgumentException("Forge knows no card called " + name + ".");
        }
        final Card c = Card.fromPaperCard(pc, owner);
        c.setGameTimestamp(g.getNextTimestamp());
        return c;
    }

    /** Puts a card in a zone of a player: hand, battlefield, graveyard, exile, command, sideboard, library (top or bottom). */
    private void place(final Game g, final Card c, final Player to, final String zone0, final JsonObject m, final boolean fresh) {
        final String zone = zone0.trim().toLowerCase(Locale.ROOT);
        if (c.getOwner() != to && c.getZone() == null) {
            c.setOwner(to);
        }
        final boolean off = !flag(m, "triggers", false);
        if (off) {
            g.getTriggerHandler().setSuppressAllTriggers(true);
        }
        try {
            switch (zone) {
                case "battlefield": {
                    if (!c.isPermanent()) {
                        throw new IllegalArgumentException(c + " isn't a permanent: it can't be on the battlefield.");
                    }
                    if (c.getController() != to) {
                        c.setController(to, g.getNextTimestamp());
                    }
                    final Card moved = g.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
                    if (moved != null) {
                        moved.setSickness(flag(m, "sick", false));
                        if (flag(m, "tapped", false)) {
                            moved.tap(true, null, null);
                        }
                    }
                    break;
                }
                case "library":
                case "library_top":
                case "library_bottom":
                    if (zone.equals("library_bottom") || !flag(m, "top", true)) {
                        g.getAction().moveToBottomOfLibrary(c, null);
                    } else {
                        g.getAction().moveToLibrary(c, null);
                    }
                    break;
                case "hand":
                    g.getAction().moveTo(to.getZone(ZoneType.Hand), c, null, AbilityKey.newMap());
                    break;
                case "graveyard":
                    g.getAction().moveTo(to.getZone(ZoneType.Graveyard), c, null, AbilityKey.newMap());
                    break;
                case "exile":
                    g.getAction().moveTo(to.getZone(ZoneType.Exile), c, null, AbilityKey.newMap());
                    break;
                case "command":
                    g.getAction().moveTo(to.getZone(ZoneType.Command), c, null, AbilityKey.newMap());
                    break;
                case "sideboard":
                    g.getAction().moveTo(to.getZone(ZoneType.Sideboard), c, null, AbilityKey.newMap());
                    break;
                default:
                    throw new IllegalArgumentException("No zone called " + zone0
                            + " (hand, battlefield, graveyard, exile, library, library_bottom, command, sideboard).");
            }
        } finally {
            if (off) {
                g.getTriggerHandler().setSuppressAllTriggers(false);
            }
        }
    }

    private String reorderTop(final Game g, final JsonObject m) {
        final Player p = player(g, m);
        final PlayerZone lib = p.getZone(ZoneType.Library);
        final List<Card> rest = new ArrayList<>();
        for (final Card c : lib.getCards()) {
            rest.add(c);
        }
        final List<Card> top = new ArrayList<>();
        if (m.has("cards") && m.get("cards").isJsonArray()) {
            for (final JsonElement e : m.getAsJsonArray("cards")) {
                final Card c = g.findById(e.getAsInt());
                if (c == null || !rest.remove(c)) {
                    throw new IllegalArgumentException("Card " + e.getAsInt() + " isn't in " + p + "'s library.");
                }
                top.add(c);
            }
        } else if (m.has("names") && m.get("names").isJsonArray()) {
            for (final JsonElement e : m.getAsJsonArray("names")) {
                final Card c = byName(rest, e.getAsString());
                if (c == null) {
                    throw new IllegalArgumentException("No card called " + e.getAsString() + " is in " + p + "'s library.");
                }
                rest.remove(c);
                top.add(c);
            }
        } else {
            throw new IllegalArgumentException("reorder_top needs names (or cards): the top card first.");
        }
        final int n = top.size();
        top.addAll(rest);
        lib.setCards(top);
        m.remove("cards");
        return "The top " + n + " of " + p + "'s library are set.";
    }

    /** `peek` {player, zone, n}: the cards of a zone that isn't shown (a library, top first; a hand) – in `cards`. */
    private String peek(final Game g, final JsonObject m) {
        final Player p = player(g, m);
        final String zone = text(m, "zone", "library").toLowerCase(Locale.ROOT);
        final ZoneType type = zone.startsWith("library") ? ZoneType.Library : ZoneType.smartValueOf(zone);
        if (type == null) {
            throw new IllegalArgumentException("No zone called " + zone + ".");
        }
        final CardCollectionView cards = p.getCardsIn(type);
        final int n = Math.min(cards.size(), number(m, "n", cards.size()));
        final JsonArray a = new JsonArray();
        final JsonView everything = new JsonView(null);
        for (int i = 0; i < n; i++) {
            a.add(everything.card(cards.get(i).getView()));
        }
        m.add("cards", a);
        return n + " cards of " + p + "'s " + zone + ".";
    }

    private String setRule(final Game g, final JsonObject m) {
        final String name = needText(m, "name").toLowerCase(Locale.ROOT);
        switch (name) {
            case "poison_to_lose":
                g.getRules().setPoisonCountersToLose(need(m, "value"));
                return "A player loses at " + need(m, "value") + " poison counters.";
            case "commander_damage":
                g.getRules().setCommanderDamageToLose(need(m, "value"));
                return "A player loses at " + need(m, "value") + " damage from one commander.";
            case "commander_tax":
                g.getRules().setCommanderTax(flag(m, "value", true));
                return flag(m, "value", true) ? "Commanders cost {2} more each time again." : "No commander tax.";
            case "draw_loss":
                g.getRules().setDrawFromEmptyLibraryLoses(flag(m, "value", true));
                return flag(m, "value", true) ? "Drawing from an empty library loses the game again."
                        : "Drawing from an empty library doesn't lose the game.";
            case "turn_limit":
                opt.turnLimit = need(m, "value");
                return opt.turnLimit > 0 ? "The game ends in a draw after turn " + opt.turnLimit + "." : "No turn limit.";
            default:
                throw new IllegalArgumentException("No rule called " + name
                        + " (poison_to_lose, commander_damage, commander_tax, draw_loss, turn_limit).");
        }
    }

    // ------------------------------------------------------------------ mana, sickness, skipping

    private void addMana(final Game g, final Player p, final String colours, final int each) {
        if (manaSource == null || manaSource.getGame() != g) {
            manaSource = new Card(-777777, g);
            manaSource.setName("Testing tools");
        }
        manaSource.setOwner(p);
        final List<Mana> mana = new ArrayList<>();
        for (final char ch : colours.toCharArray()) {
            final byte colour = ch == 'C' ? (byte) ManaAtom.COLORLESS : MagicColor.fromName(String.valueOf(ch));
            for (int i = 0; i < each; i++) {
                mana.add(new Mana(colour, manaSource, null, p));
            }
        }
        p.getManaPool().addMana(mana);
    }

    private static String colours(final String text) {
        final StringBuilder sb = new StringBuilder();
        for (final char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (COLOURS.indexOf(ch) >= 0) {
                sb.append(ch);
            }
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("No colours in \"" + text + "\" (W U B R G, C for colourless).");
        }
        return sb.toString();
    }

    /** The switches that stay on: tops up mana pools, takes summoning sickness away. True when something changed. */
    private boolean switches(final Game g) {
        for (final Map.Entry<Integer, String> e : endlessMana.entrySet()) {
            final Player p = g.getPlayer(e.getKey());
            if (p == null || !p.isInGame()) {
                continue;
            }
            for (final char ch : e.getValue().toCharArray()) {
                final byte colour = ch == 'C' ? (byte) ManaAtom.COLORLESS : MagicColor.fromName(String.valueOf(ch));
                final int has = p.getManaPool().getAmountOfColor(colour);
                if (has < 20) {
                    addMana(g, p, String.valueOf(ch), 20 - has);
                    gui.devChanged();
                }
            }
        }
        if (!noSickness.isEmpty()) {
            for (final Card c : g.getCardsIn(ZoneType.Battlefield)) {
                if (c.hasSickness() && (noSickness.containsKey(-1) || noSickness.containsKey(c.getController().getId()))) {
                    c.setSickness(false);
                    gui.devChanged();
                }
            }
        }
        return false;
    }

    private String skipTo(final Game g, final JsonObject m) {
        final PhaseHandler ph = g.getPhaseHandler();
        String what = text(m, "what", "next_turn").trim();
        PhaseType phase = null;
        if (what.toLowerCase(Locale.ROOT).startsWith("phase")) {
            final String name = what.length() > 5 ? what.substring(5).trim() : text(m, "phase", "");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("skip_to phase needs a phase (UPKEEP, DRAW, MAIN1, COMBAT_BEGIN… END_OF_TURN).");
            }
            phase = PhaseType.valueOf(name.toUpperCase(Locale.ROOT));
            what = "phase";
        }
        what = what.toLowerCase(Locale.ROOT);
        int who = -1;
        final PlayerControllerHuman h = gui.human();
        final Player me = h == null ? g.getPlayers().get(0) : h.getPlayer();
        switch (what) {
            case "phase":
                if (h == null) {
                    throw new IllegalArgumentException("Nobody is playing: there is no priority to pass. Use next_turn.");
                }
                if (m.has("player")) {
                    who = player(g, m).getId();
                }
                break;
            case "next_turn":
                break;
            case "my_next_turn":
                who = me.getId();
                break;
            case "opponent_turn":
                who = m.has("player") ? player(g, m).getId() : g.getNextPlayerAfter(me).getId();
                break;
            case "player_turn":
                who = player(g, m).getId();
                break;
            default:
                throw new IllegalArgumentException("skip_to: next_turn, my_next_turn, opponent_turn, player_turn or phase NAME – not " + what + ".");
        }
        skip = new Skip(m, phase == null, who, phase, ph.getTurn(), ph.getPhase());
        final String text = phase != null ? "Passing until " + phase.nameForUi + "."
                : "Skipping to " + (who < 0 ? "the next turn." : g.getPlayer(who) + "'s next turn.");
        result(m, true, "started", text);
        return text;
    }

    /** Carries a `skip_to` on: ends the turns in between, says when it's there. True when the game was changed. */
    private boolean stepSkip(final Game g) {
        final Skip s = skip;
        if (s == null) {
            return false;
        }
        final PhaseHandler ph = g.getPhaseHandler();
        final boolean later = ph.getTurn() > s.fromTurn();
        final boolean theirs = s.player() < 0 || ph.getPlayerTurn().getId() == s.player();
        final boolean there = s.endTurns() ? later && theirs
                : theirs && ph.getPhase() == s.phase() && (later || s.fromPhase() != s.phase());
        if (there) {
            skip = null;
            result(s.message(), true, "done", "Now at " + ph.getPlayerTurn() + "'s " + ph.getPhase().nameForUi + ", turn " + ph.getTurn() + ".");
            gui.devChanged();
            return false;
        }
        if (s.endTurns() && g.getStack().isEmpty() && ph.getPhase() != PhaseType.CLEANUP) {
            // as "end the turn" does (Time Stop), with nothing on the stack
            g.getTriggerHandler().clearWaitingTriggers();
            ph.endCombat();
            ph.endTurnByEffect();
            return true;
        }
        return false;
    }

    // ================================================================== the start of the game

    /** True when the game needs something done before its first turn (BridgeMain then sets Forge's start hook). */
    boolean hasStart() {
        return !opt.poison.isEmpty() || !opt.counters.isEmpty() || opt.startingBoard != null;
    }

    /** Forge's start hook: on the game thread, after the opening hands and mulligans, before the first turn. */
    void applyStart() {
        final Game g = game;
        if (g == null) {
            return;
        }
        try {
            final List<Player> players = g.getPlayers();
            for (int i = 0; i < players.size(); i++) {
                final Player p = players.get(i);
                final int poison = BridgeOptions.forSeat(opt.poison, i, 0);
                if (poison > 0) {
                    p.setPoisonCounters(poison, p);
                }
                final String counters = i < opt.counters.size() ? opt.counters.get(i) : "";
                for (final String part : counters.split("[+;]")) {
                    if (part.contains("=")) {
                        final String[] kv = part.split("=", 2);
                        p.setCounters(counterType(kv[0]), Integer.parseInt(kv[1].trim()), p, true);
                    }
                }
            }
            if (opt.startingBoard != null) {
                final String text = Files.readString(Paths.get(opt.startingBoard), StandardCharsets.UTF_8);
                if (text.trim().startsWith("{")) {
                    board(g, JsonParser.parseString(text).getAsJsonObject());
                } else {
                    forgeState(g, text);
                }
            }
            g.getAction().checkStateEffects(true);
            g.copyLastState();
        } catch (final IOException | RuntimeException e) {
            e.printStackTrace();
            opt.problems.add("The starting board couldn't be set up: " + e.getMessage());
            gui.error("The starting board couldn't be set up: " + e.getMessage());
        }
        gui.devChanged();
    }

    /** A position in Forge's own form (its dev mode's game state files, its puzzles): every zone is replaced. */
    private void forgeState(final Game g, final String text) {
        final GameState state = new GameState();
        state.parse(Arrays.asList(text.split("\r?\n")));
        state.applyToGame(g);       // on this (the game) thread: done when it returns
        g.updateTurnForView();
        g.updatePhaseForView();
        g.updatePlayerTurnForView();
    }

    /**
     * A starting board as JSON: {players: [{battlefield, hand, graveyard, exile, command, library_top,
     * library_bottom, sideboard: [names or {name, tapped, sick, counters}], life, poison, counters,
     * replace_hand}], turn, phase, active} – a player a seat, in order (or under "me" / "opponent" / "p0"…).
     * A card is taken from that player's library when one of that name is there, else it is made.
     * With "forge_state" (what `export_state` writes) that exact position is set up instead.
     */
    private void board(final Game g, final JsonObject board) {
        if (board.has("forge_state") && board.get("forge_state").isJsonPrimitive()) {
            forgeState(g, board.get("forge_state").getAsString());
            return;
        }
        final List<Player> players = g.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            JsonObject side = null;
            if (board.has("players") && board.get("players").isJsonArray() && i < board.getAsJsonArray("players").size()
                    && board.getAsJsonArray("players").get(i).isJsonObject()) {
                side = board.getAsJsonArray("players").get(i).getAsJsonObject();
            }
            for (final String key : new String[] {"p" + i, i == 0 ? "me" : i == 1 ? "opponent" : "opponent" + i }) {
                if (side == null && board.has(key) && board.get(key).isJsonObject()) {
                    side = board.getAsJsonObject(key);
                }
            }
            if (side != null) {
                side(g, players.get(i), side);
            }
        }
        final PhaseHandler ph = g.getPhaseHandler();
        if (board.has("turn") || board.has("phase") || board.has("active")) {
            final PhaseType phase = board.has("phase") && !board.get("phase").isJsonNull()
                    ? PhaseType.valueOf(board.get("phase").getAsString().toUpperCase(Locale.ROOT)) : null;
            final Player active = board.has("active") && !board.get("active").isJsonNull()
                    ? players.get(Math.max(0, Math.min(players.size() - 1, board.get("active").getAsInt()))) : null;
            ph.devModeSet(phase, active, board.has("turn") ? board.get("turn").getAsInt() : ph.getTurn());
        }
        g.updateTurnForView();
        g.updatePhaseForView();
        g.updatePlayerTurnForView();
    }

    private void side(final Game g, final Player p, final JsonObject side) {
        if (flag(side, "replace_hand", false)) {
            for (final Card c : new ArrayList<>(p.getCardsIn(ZoneType.Hand))) {
                g.getAction().moveToLibrary(c, null);
            }
            p.shuffle(null);
        }
        final JsonObject none = new JsonObject();
        for (final String zone : BOARD_ZONES) {
            if (!side.has(zone) || !side.get(zone).isJsonArray()) {
                continue;
            }
            final JsonArray list = side.getAsJsonArray(zone);
            // the top of the library is written top first: put the last one there first
            for (int n = 0; n < list.size(); n++) {
                final JsonElement e = list.get(zone.equals("library_top") ? list.size() - 1 - n : n);
                final JsonObject entry = e.isJsonObject() ? e.getAsJsonObject() : none;
                final String name = e.isJsonObject() ? needText(entry, "name") : e.getAsString();
                try {
                    Card c = byName(p.getCardsIn(ZoneType.Library), name);
                    if (c == null) {
                        c = newCard(g, p, name);
                    }
                    place(g, c, p, zone, entry, true);
                    if (entry.has("counters") && entry.get("counters").isJsonObject()) {
                        final Card there = g.getCardState(c);
                        for (final Map.Entry<String, JsonElement> kv : entry.getAsJsonObject("counters").entrySet()) {
                            there.addCounterInternal(counterType(kv.getKey()), kv.getValue().getAsInt(), p, false, null, null);
                        }
                    }
                } catch (final IllegalArgumentException ex) {
                    opt.problems.add(ex.getMessage());
                }
            }
        }
        if (side.has("life")) {
            p.setLife(side.get("life").getAsInt(), null);
        }
        if (side.has("poison")) {
            p.setPoisonCounters(side.get("poison").getAsInt(), p);
        }
        if (side.has("counters") && side.get("counters").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> kv : side.getAsJsonObject("counters").entrySet()) {
                p.setCounters(counterType(kv.getKey()), kv.getValue().getAsInt(), p, true);
            }
        }
    }

    // ================================================================== scenarios

    /** `export_state` {path?}: the position as a `scenario` message (and a file, when a path is given). */
    private void export(final Game g, final JsonObject m) {
        final GameState state = new GameState();
        state.initFromGame(g);
        final String text = state.toString();
        final JsonObject board = new JsonObject();
        board.addProperty("format", "mse-play-scenario");
        board.addProperty("version", 1);
        final PhaseHandler ph = g.getPhaseHandler();
        board.addProperty("turn", ph.getTurn());
        board.addProperty("phase", ph.getPhase() == null ? null : ph.getPhase().name());
        board.addProperty("active", g.getPlayers().indexOf(ph.getPlayerTurn()));
        board.addProperty("game_type", g.getRules().getGameType().name());
        final JsonArray players = new JsonArray();
        for (final Player p : g.getPlayers()) {
            final JsonObject side = new JsonObject();
            side.addProperty("name", p.getName());
            side.addProperty("life", p.getLife());
            side.addProperty("poison", p.getPoisonCounters());
            side.add("counters", JsonView.counters(p.getCounters()));
            for (final ZoneType zone : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard,
                    ZoneType.Exile, ZoneType.Command, ZoneType.Library, ZoneType.Sideboard }) {
                final JsonArray cards = new JsonArray();
                for (final Card c : p.getCardsIn(zone)) {
                    if (c.isImmutable()) {
                        continue;       // the commander effect and the like: Forge makes them again
                    }
                    if (zone == ZoneType.Battlefield) {
                        final JsonObject entry = new JsonObject();
                        entry.addProperty("name", c.getName());
                        entry.addProperty("tapped", c.isTapped());
                        entry.addProperty("sick", c.hasSickness());
                        entry.addProperty("token", c.isToken());
                        entry.add("counters", JsonView.counters(c.getCounters()));
                        cards.add(entry);
                    } else {
                        cards.add(c.getName());
                    }
                }
                side.add(zone == ZoneType.Library ? "library_top" : zone.name().toLowerCase(Locale.ROOT), cards);
            }
            players.add(side);
        }
        board.add("players", players);
        board.addProperty("forge_state", text);
        final JsonObject o = new JsonObject();
        o.addProperty("t", "scenario");
        if (m.has("id")) {
            o.add("id", m.get("id"));
        }
        o.addProperty("format", "forge-state");
        o.addProperty("text", text);
        o.add("json", board);
        o.addProperty("turn", ph.getTurn());
        o.addProperty("phase", ph.getPhase() == null ? null : ph.getPhase().name());
        o.addProperty("hash", hash(g));
        String saved = null;
        if (m.has("path") && m.get("path").isJsonPrimitive()) {
            final Path path = Paths.get(m.get("path").getAsString());
            try {
                Files.writeString(path, path.toString().toLowerCase(Locale.ROOT).endsWith(".txt") ? text
                        : BridgeWire.toJson(board), StandardCharsets.UTF_8);
                saved = path.toAbsolutePath().toString();
            } catch (final IOException e) {
                o.addProperty("error", "The file couldn't be written: " + e.getMessage());
            }
        }
        o.addProperty("path", saved);
        wire.send(o);
    }

    // ================================================================== checkpoints

    /** Game events, on the game thread: a `checkpoint` at every turn's start and every N actions. */
    void onEvent(final GameEvent ev) {
        final Game g = game;
        if (opt.checkpoints < 0 || g == null) {
            return;
        }
        if (ev instanceof GameEventTurnBegan) {
            checkpoint(g, "turn");
        } else if (ev instanceof GameEventGameOutcome) {
            checkpoint(g, "end");
        } else if (opt.checkpoints > 0 && (ev instanceof GameEventSpellAbilityCast || ev instanceof GameEventSpellResolved
                || ev instanceof GameEventLandPlayed || ev instanceof GameEventAttackersDeclared
                || ev instanceof GameEventBlockersDeclared) && ++actions % opt.checkpoints == 0) {
            checkpoint(g, "actions");
        }
    }

    void checkpoint(final Game g, final String why) {
        if (opt.checkpoints < 0) {
            return;
        }
        try {
            final JsonObject o = new JsonObject();
            o.addProperty("t", "checkpoint");
            o.addProperty("seq", checkpoints++);
            o.addProperty("why", why);
            final PhaseHandler ph = g.getPhaseHandler();
            o.addProperty("turn", ph.getTurn());
            o.addProperty("phase", ph.getPhase() == null ? null : ph.getPhase().name());
            o.add("active", ph.getPlayerTurn() == null ? null : new com.google.gson.JsonPrimitive(ph.getPlayerTurn().getId()));
            o.addProperty("tick", tick);
            o.addProperty("hash", hash(g));
            wire.send(o);
        } catch (final RuntimeException e) {
            gui.error("The bridge couldn't make a checkpoint: " + e);
        }
    }

    /**
     * The game as one short word: the turn, the phase, whose turn, and for every player their life,
     * counters and the ids of their cards zone by zone, in order (the library too – nothing of it is
     * told, only whether it is the same) with what's tapped and its counters, and the stack. The same
     * game played the same way gives the same word.
     */
    static String hash(final Game g) {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append(ph.getTurn()).append('|').append(ph.getPhase()).append('|')
                .append(ph.getPlayerTurn() == null ? -1 : ph.getPlayerTurn().getId());
        for (final Player p : g.getRegisteredPlayers()) {
            sb.append("\nP").append(p.getId()).append(':').append(p.getLife()).append(':').append(sorted(p.getCounters()))
                    .append(':').append(p.hasLost());
            for (final ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile,
                    ZoneType.Command, ZoneType.Battlefield, ZoneType.Sideboard }) {
                sb.append('\n').append(zone.name()).append('=');
                for (final Card c : p.getCardsIn(zone)) {
                    sb.append(c.getId());
                    if (zone == ZoneType.Battlefield) {
                        sb.append(c.isTapped() ? "T" : "").append(sorted(c.getCounters())).append('d').append(c.getDamage());
                    }
                    sb.append(',');
                }
            }
        }
        sb.append("\nS=");
        for (final SpellAbilityStackInstance si : g.getStack()) {
            sb.append(si.getSourceCard() == null ? -1 : si.getSourceCard().getId()).append(',');
        }
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", d[i]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String sorted(final Multiset<CounterType> counters) {
        final Map<String, Integer> map = new TreeMap<>();
        if (counters != null) {
            for (final Multiset.Entry<CounterType> e : counters.entrySet()) {
                if (e.getCount() > 0) {
                    map.put(e.getElement().getName(), e.getCount());
                }
            }
        }
        return map.toString();
    }

    // ================================================================== reading a message

    /** `player`: an id, or "me", "opponent", "active", "priority". Left out: the person playing (else the first seat). */
    private Player player(final Game g, final JsonObject m) {
        final PlayerControllerHuman h = gui.human();
        final Player me = h != null && h.getPlayer() != null ? h.getPlayer() : g.getRegisteredPlayers().get(0);
        if (!m.has("player") || m.get("player").isJsonNull()) {
            return me;
        }
        final String text = m.get("player").getAsString().trim().toLowerCase(Locale.ROOT);
        switch (text) {
            case "me":
                return me;
            case "opponent":
                for (final Player p : g.getPlayers()) {
                    if (p != me) {
                        return p;
                    }
                }
                throw new IllegalArgumentException("There is no opponent left.");
            case "active":
                return g.getPhaseHandler().getPlayerTurn();
            case "priority":
                return g.getPhaseHandler().getPriorityPlayer();
            default:
                try {
                    final int id = Integer.parseInt(text);
                    for (final Player p : g.getRegisteredPlayers()) {
                        if (p.getId() == id) {
                            return p;
                        }
                    }
                } catch (final NumberFormatException e) {
                    // said below
                }
                throw new IllegalArgumentException("No player " + text + ".");
        }
    }

    private static Card card(final Game g, final JsonObject m) {
        if (!m.has("card") || !m.get("card").isJsonPrimitive()) {
            throw new IllegalArgumentException("This needs a card (its id).");
        }
        final int id;
        try {
            id = m.get("card").getAsInt();
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("This needs a card's id (a number), not " + m.get("card") + ".");
        }
        final Card c = g.findById(id);
        if (c == null) {
            throw new IllegalArgumentException("Card " + id + " isn't in the game.");
        }
        return c;
    }

    private static int need(final JsonObject m, final String key) {
        if (!m.has(key) || !m.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("This needs \"" + key + "\" (a number).");
        }
        return m.get(key).getAsInt();
    }

    private static int number(final JsonObject m, final String key, final int otherwise) {
        return m.has(key) && m.get(key).isJsonPrimitive() ? m.get(key).getAsInt() : otherwise;
    }

    private static String needText(final JsonObject m, final String key) {
        if (!m.has(key) || !m.get(key).isJsonPrimitive() || m.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("This needs \"" + key + "\".");
        }
        return m.get(key).getAsString().trim();
    }

    private static String text(final JsonObject m, final String key, final String otherwise) {
        return m.has(key) && m.get(key).isJsonPrimitive() ? m.get(key).getAsString() : otherwise;
    }

    private static boolean flag(final JsonObject m, final String key, final boolean otherwise) {
        if (!m.has(key) || !m.get(key).isJsonPrimitive()) {
            return otherwise;
        }
        return m.get(key).getAsJsonPrimitive().isBoolean() ? m.get(key).getAsBoolean() : BridgeOptions.yes(m.get(key).getAsString());
    }

    boolean isRunning() {
        return running;
    }
}
