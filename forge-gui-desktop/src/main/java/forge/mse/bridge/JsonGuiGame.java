package forge.mse.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardPhased;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.Selection;
import forge.gui.control.PlaybackSpeed;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * Forge's game window, as messages: the {@link forge.gui.interfaces.IGuiGame} Forge talks to
 * while a game runs, writing what happens as JSON lines on a {@link BridgeWire} for the MSE Play
 * table (the messages: plans/mse-play.md in the engine repository).
 *
 * Three kinds of calls arrive here, on different threads:
 * <ul>
 * <li><b>Game events</b> ({@link #onGameEvent}, subscribed straight to the game's event bus):
 * on Forge's game thread, in the middle of whatever the game is doing. This is where `state`,
 * `cards`, `log` and `game_over` are made – the only place the views are safe to read, because
 * the game thread is the one that changes them.</li>
 * <li><b>Window updates</b> (updateZones, updateCards, showPromptMessage, updateButtons…): on
 * Swing's event thread, later, gathered by Forge's FControlGameEventHandler. Only the prompt
 * (message and buttons) is taken from these; the rest is already covered by the events.</li>
 * <li><b>Questions</b> (confirm, getChoices, order, assignCombatDamage…): on the game thread,
 * which waits for the return value. All of them go through {@link #ask}.</li>
 * </ul>
 *
 * In M0 nobody plays through this window (it watches a game between AI players), so
 * {@link #interactive} is false and every question is sent as a `prompt` and answered at once
 * with its default. A human seat (M2) sets it true: {@link #ask} then waits for the table's
 * `answer`, and {@link #awaitInput} sends a fresh `state` before the game thread waits for a
 * click.
 */
public class JsonGuiGame extends AbstractGuiGame {
    public static final int PROTOCOL = 1;

    private final BridgeWire wire;
    private final long seed;
    private final PlaybackSpeed wantedSpeed;
    private volatile boolean interactive = false;

    private volatile Game game;
    private JsonView view = new JsonView(null);
    private int logSent = 0;
    private String lastState = "";
    private boolean dirty = true;
    private boolean speedSet = false;
    private volatile boolean resync = false;
    private volatile boolean gameOverSent = false;

    private final AtomicInteger promptIds = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonElement>> waiting = new ConcurrentHashMap<>();
    // the prompt Forge shows the player between questions: a message and two buttons
    private final Object promptLock = new Object();
    private int inputPromptId = 0;
    private String promptMessage = "";
    private JsonElement promptPlayer = JsonNull.INSTANCE;
    private JsonElement promptCard = JsonNull.INSTANCE;
    private JsonObject promptButtons = buttons("OK", "Cancel", false, false, true);
    private JsonObject promptSelection = null;

    public JsonGuiGame(final BridgeWire wire0, final long seed0, final PlaybackSpeed speed) {
        wire = wire0;
        seed = seed0;
        wantedSpeed = speed;
        wire.setListener(this::onMessage);
        wire.setOnConnect(() -> resync = true);
    }

    /** True once a person plays through this window: questions then wait for the table's answer. */
    public void setInteractive(final boolean interactive0) {
        interactive = interactive0;
    }

    public boolean isGameOverSent() {
        return gameOverSent;
    }

    // ================================================================== the game, on the game thread

    /** HostedMatch calls this once the game exists and before it starts: the place to listen from. */
    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        final GameView gv = getGameView();
        final Game g = gv == null ? null : gv.getGame();
        if (g == null || g == game) {
            return;
        }
        game = g;
        final PlayerView me = myPlayers == null || myPlayers.isEmpty() ? null : myPlayers.get(0);
        view = new JsonView(me);
        logSent = 0;
        lastState = "";
        gameOverSent = false;
        g.subscribeToEvents(this);
        final GameLog log = g.getGameLog();
        log.addObserver(new LogObserver());
        sendHello();
        resync = false;
    }

    private void sendHello() {
        final Game g = game;
        final JsonObject o = msg("hello");
        o.addProperty("version", PROTOCOL);
        o.addProperty("forge", forge.gui.GuiBase.getInterface().getCurrentVersion());
        final PlayerView me = getCurrentPlayer();
        o.add("me", JsonView.id(me));
        o.addProperty("watching", me == null);
        o.addProperty("game_type", g.getRules().getGameType().name());
        o.addProperty("title", getGameView().getTitle());
        o.addProperty("seed", seed);
        final JsonArray players = new JsonArray();
        for (final Player p : g.getRegisteredPlayers()) {
            final JsonObject po = new JsonObject();
            po.addProperty("id", p.getId());
            po.addProperty("name", p.getName());
            po.addProperty("ai", p.getView().isAI());
            final RegisteredPlayer rp = p.getRegisteredPlayer();
            po.addProperty("deck", rp == null || rp.getDeck() == null ? null : rp.getDeck().getName());
            po.addProperty("life", p.getStartingLife());
            players.add(po);
        }
        o.add("players", players);
        final JsonArray zones = new JsonArray();
        for (final String z : JsonView.ZONE_NAMES) {
            zones.add(z);
        }
        o.add("zones", zones);
        wire.send(o);
    }

    @Subscribe
    public void onGameEvent(final GameEvent ev) {
        try {
            if (ev instanceof GameEventCardTapped e) {
                changed(Collections.singleton(e.card()));
            } else if (ev instanceof GameEventCardCounters e) {
                changed(Collections.singleton(e.card()));
            } else if (ev instanceof GameEventCardDamaged e) {
                changed(Collections.singleton(e.card()));
            } else if (ev instanceof GameEventCardPhased e) {
                changed(Collections.singleton(e.card()));
            } else if (ev instanceof GameEventCardAttachment e) {
                changed(Collections.singleton(e.equipment()));
            } else if (ev instanceof GameEventCardStatsChanged e) {
                changed(e.cards());
            } else if (ev instanceof GameEventGameOutcome e) {
                dirty = true;
                sendState();
                sendGameOver(e);
            } else if (ev instanceof GameEventGameStarted || ev instanceof GameEventTurnBegan
                    || ev instanceof GameEventTurnPhase || ev instanceof GameEventPlayerPriority
                    || ev instanceof GameEventMulligan || ev instanceof GameEventAttackersDeclared
                    || ev instanceof GameEventBlockersDeclared || ev instanceof GameEventCombatEnded
                    || ev instanceof GameEventSpellAbilityCast || ev instanceof GameEventSpellResolved
                    || ev instanceof GameEventSpellRemovedFromStack || ev instanceof GameEventLandPlayed) {
                dirty = true;
                sendState();
                if (!speedSet && ev instanceof GameEventPlayerPriority) {
                    speedSet = true;
                    setSpeed();
                }
            } else {
                dirty = true;       // zones, life, mana…: in the next state
            }
        } catch (final RuntimeException e) {
            error("The bridge couldn't describe " + ev.getClass().getSimpleName() + ": " + e);
        }
    }

    /** The whole game, if anything changed since the last one sent. Game thread only. */
    private void sendState() {
        final GameView gv = getGameView();
        if (gv == null || !dirty && !resync) {
            return;
        }
        if (resync) {           // a table connected in the middle: start it from the beginning
            resync = false;
            lastState = "";
            sendHello();
            showPrompt(false);
        }
        dirty = false;
        final JsonObject o = view.state(gv);
        final String text = BridgeWire.toJson(o);
        if (text.equals(lastState)) {
            return;
        }
        lastState = text;
        wire.send(o);
    }

    /** Some cards changed where they are (tapped, counters, damage, size): the cheap update. */
    private void changed(final Iterable<CardView> cards) {
        dirty = true;
        final JsonObject o = msg("cards");
        final JsonArray a = view.cards(cards);
        if (a.isEmpty()) {
            return;
        }
        o.add("cards", a);
        wire.send(o);
    }

    private void sendGameOver(final GameEventGameOutcome e) {
        final Game g = game;
        final JsonObject o = msg("game_over");
        final GameOutcome outcome = g.getOutcome();
        JsonElement winner = JsonNull.INSTANCE;
        final JsonArray players = new JsonArray();
        final LobbyPlayer won = outcome == null ? null : outcome.getWinningLobbyPlayer();
        for (final Player p : g.getRegisteredPlayers()) {
            final JsonObject po = new JsonObject();
            po.addProperty("id", p.getId());
            po.addProperty("name", p.getName());
            final boolean isWinner = won != null && won.equals(p.getLobbyPlayer());
            po.addProperty("won", isWinner);
            po.addProperty("life", p.getLife());
            po.addProperty("result", outcome == null || p.getRegisteredPlayer() == null ? null
                    : outcome.getOutcomeString(p.getRegisteredPlayer()));
            players.add(po);
            if (isWinner) {
                winner = new JsonPrimitive(p.getId());
            }
        }
        o.add("winner", winner);
        o.addProperty("winner_name", e.winningPlayerName());
        o.addProperty("draw", outcome != null && outcome.isDraw());
        o.addProperty("reason", outcome == null || outcome.getWinCondition() == null ? null
                : outcome.getWinCondition().name());
        o.addProperty("turns", e.lastTurnNumber());
        o.add("players", players);
        final JsonArray lines = new JsonArray();
        for (final String s : e.outcomeStrings()) {
            lines.add(s);
        }
        o.add("text", lines);
        wire.send(o);
        gameOverSent = true;
    }

    /** Forge's own game log: each new line as a `log` message (on the game thread, as it's written). */
    @SuppressWarnings("deprecation")
    private final class LogObserver implements Observer {
        @Override
        public void update(final Observable o, final Object arg) {
            try {
                final List<GameLogEntry> all = ((GameLog) o).getAllEntries();
                if (all.size() < logSent) {      // the log was cut back (an undo)
                    logSent = all.size();
                }
                for (int i = logSent; i < all.size(); i++) {      // oldest first
                    final GameLogEntry entry = all.get(i);
                    final JsonObject m = msg("log");
                    m.addProperty("kind", entry.type().name());
                    m.addProperty("text", entry.message());
                    if (entry.sourceCard() != null) {
                        m.addProperty("card", entry.sourceCard().getId());
                    }
                    wire.send(m);
                }
                logSent = all.size();
            } catch (final RuntimeException e) {
                error("The bridge couldn't read the game log: " + e);
            }
        }
    }

    /** A watched game starts at Forge's normal speed; its Cancel button steps normal → fast → slow. */
    private void setSpeed() {
        final IGameController c = getGameController();
        if (interactive || c == null) {
            return;
        }
        for (PlaybackSpeed s = PlaybackSpeed.NORMAL; s != wantedSpeed; s = s.nextSpeed()) {
            c.selectButtonCancel();
        }
    }

    /**
     * The game thread is about to wait for the player's click (an Input*): send what the game
     * looks like now, then wait. The prompt itself comes through showPromptMessage and
     * updateButtons on the event thread, so it may arrive just before or just after this state.
     */
    @Override
    public void awaitInput(final CountDownLatch done) throws InterruptedException {
        dirty = true;
        try {
            sendState();
        } catch (final RuntimeException e) {
            error("The bridge couldn't describe the game: " + e);
        }
        done.await();
    }

    // ================================================================== what the table sends

    private void onMessage(final JsonObject m) {
        final String t = m.get("t").getAsString();
        switch (t) {
            case "answer": {
                final CompletableFuture<JsonElement> f = waiting.remove(m.get("prompt").getAsInt());
                if (f == null) {
                    error("No question is waiting for answer " + m.get("prompt"));
                } else {
                    f.complete(m.has("value") ? m.get("value") : JsonNull.INSTANCE);
                }
                break;
            }
            case "ok":          // watching: pause / resume
                control(IGameController::selectButtonOk);
                break;
            case "cancel":      // watching: the next speed, or one step while paused
                control(IGameController::selectButtonCancel);
                break;
            case "concede":
                control(IGameController::concede);
                break;
            case "undo":
                control(IGameController::undoLastAction);
                break;
            case "card":
                control(c -> c.selectCard(cardById(m.get("id").getAsInt()), null, null));
                break;
            case "player":
                control(c -> c.selectPlayer(playerById(m.get("id").getAsInt()), null));
                break;
            case "mana":
                control(c -> c.useMana(forge.card.MagicColor.fromName(m.get("colour").getAsString())));
                break;
            case "quit":
                BridgeMain.finish(0);
                break;
            default:
                error("The bridge doesn't know the message \"" + t + "\"");
        }
    }

    /** The player's actions go to Forge on the event thread, as its own window's clicks do. */
    private void control(final java.util.function.Consumer<IGameController> action) {
        forge.gui.FThreads.invokeInEdtLater(() -> {
            final IGameController c = getGameController();
            if (c == null) {
                error("No game is running");
                return;
            }
            try {
                action.accept(c);
            } catch (final RuntimeException e) {
                error("Forge refused that: " + e);
            }
        });
    }

    private CardView cardById(final int id) {
        final GameView gv = getGameView();
        if (gv != null) {
            for (final PlayerView p : gv.getPlayers()) {
                for (final forge.game.zone.ZoneType z : forge.game.zone.ZoneType.values()) {
                    for (final CardView c : p.getCards(z)) {
                        if (c.getId() == id) {
                            return c;
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("no card " + id);
    }

    private PlayerView playerById(final int id) {
        final GameView gv = getGameView();
        if (gv != null) {
            for (final PlayerView p : gv.getPlayers()) {
                if (p.getId() == id) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException("no player " + id);
    }

    // ================================================================== messages

    private static JsonObject msg(final String type) {
        final JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    public void error(final String text) {
        final JsonObject o = msg("error");
        o.addProperty("text", text);
        wire.send(o);
    }

    private static JsonObject buttons(final String ok, final String cancel, final boolean okOn, final boolean cancelOn,
            final boolean focusOk) {
        final JsonObject b = new JsonObject();
        final JsonObject o = new JsonObject();
        o.addProperty("label", ok);
        o.addProperty("enabled", okOn);
        final JsonObject c = new JsonObject();
        c.addProperty("label", cancel);
        c.addProperty("enabled", cancelOn);
        b.add("ok", o);
        b.add("cancel", c);
        b.addProperty("focus", focusOk ? "ok" : "cancel");
        return b;
    }

    /** The prompt between questions (kind `watch` while only watching, else `input`). */
    private void showPrompt(final boolean isNew) {
        final JsonObject o = msg("prompt");
        synchronized (promptLock) {
            if (isNew) {
                inputPromptId = promptIds.incrementAndGet();
            }
            o.addProperty("id", inputPromptId);
            o.addProperty("kind", interactive ? "input" : "watch");
            o.addProperty("wait", false);
            o.add("player", promptPlayer);
            o.addProperty("message", promptMessage);
            o.add("card", promptCard);
            o.add("buttons", promptButtons);
            if (promptSelection != null) {
                o.add("selection", promptSelection);
            }
        }
        wire.send(o);
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        synchronized (promptLock) {
            promptPlayer = JsonView.id(playerView);
            promptMessage = message == null ? "" : message;
            promptCard = JsonView.id(card);
        }
        showPrompt(true);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1,
            final boolean enable2, final boolean focus1) {
        synchronized (promptLock) {
            promptButtons = buttons(label1, label2, enable1, enable2, focus1);
        }
        showPrompt(false);
    }

    /** What the player may click for the prompt that's showing (targets, attackers, cards to pay with…). */
    @Override
    public void setSelection(final Selection next) {
        super.setSelection(next);
        final JsonObject s = new JsonObject();
        s.add("cards", JsonView.ids(next.cards()));
        s.addProperty("min", next.min());
        s.addProperty("max", next.max());
        final JsonArray targets = new JsonArray();
        for (final TrackableObject t : next.targets()) {
            targets.add(thing(t));
        }
        s.add("targets", targets);
        final JsonArray chosen = new JsonArray();
        for (final TrackableObject t : next.chosen()) {
            chosen.add(thing(t));
        }
        s.add("chosen", chosen);
        synchronized (promptLock) {
            promptSelection = next == Selection.NONE ? null : s;
        }
        if (interactive) {
            showPrompt(false);
        }
    }

    /** {card: id}, {player: id} or {stack: id}, with its name. */
    private static JsonObject thing(final Object x) {
        final JsonObject o = new JsonObject();
        if (x instanceof GameEntityView e) {
            return JsonView.entity(e);
        }
        if (x instanceof StackItemView s) {
            o.addProperty("stack", s.getId());
        }
        o.addProperty("name", String.valueOf(x));
        return o;
    }

    // ================================================================== questions

    /**
     * A question that needs an answer before the game can go on. Sends `prompt` {id, kind, wait, …}
     * and, when someone plays through this window, waits for `answer` {prompt: id, value}.
     * Watching (M0): returns `fallback` at once – `wait` is false in the message.
     */
    private JsonElement ask(final String kind, final JsonObject body, final JsonElement fallback) {
        final int id = promptIds.incrementAndGet();
        body.addProperty("t", "prompt");
        body.addProperty("id", id);
        body.addProperty("kind", kind);
        body.addProperty("wait", interactive);
        body.add("default", fallback);
        if (!interactive) {
            wire.send(body);
            return fallback;
        }
        if (!forge.gui.FThreads.isGuiThread()) {     // the game thread: show the table the game as it is now
            dirty = true;
            sendState();
        }
        final CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        waiting.put(id, answer);
        wire.send(body);
        try {
            final JsonElement value = answer.get();
            return value == null ? fallback : value;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (final ExecutionException e) {
            return fallback;
        } finally {
            waiting.remove(id);
        }
    }

    /** A question the table can't answer yet: sent so it can be seen, answered here with a default. */
    private void tell(final String kind, final JsonObject body) {
        body.addProperty("t", "prompt");
        body.addProperty("id", promptIds.incrementAndGet());
        body.addProperty("kind", kind);
        body.addProperty("wait", false);
        body.addProperty("handled", false);
        wire.send(body);
    }

    /** Lets every waiting question go with its default (the game ended, the table left). */
    public void releaseQuestions() {
        for (final CompletableFuture<JsonElement> f : new ArrayList<>(waiting.values())) {
            f.complete(null);
        }
    }

    private static <T> JsonArray options(final List<T> choices, final FSerializableFunction<T, String> display) {
        final JsonArray a = new JsonArray();
        if (choices != null) {
            int i = 0;
            for (final T c : choices) {
                final JsonObject o = c instanceof TrackableObject ? thing(c) : new JsonObject();
                o.addProperty("i", i++);
                o.addProperty("text", display != null ? display.apply(c) : String.valueOf(c));
                a.add(o);
            }
        }
        return a;
    }

    private static JsonArray indexes(final int from, final int count) {
        final JsonArray a = new JsonArray();
        for (int i = 0; i < count; i++) {
            a.add(from + i);
        }
        return a;
    }

    /** The choices at the answered indexes (bad ones are left out, repeats too). */
    private static <T> List<T> pick(final List<T> choices, final JsonElement value) {
        final Map<Integer, T> picked = new LinkedHashMap<>();
        if (value != null && value.isJsonArray()) {
            for (final JsonElement e : value.getAsJsonArray()) {
                final int i = e.getAsInt();
                if (i >= 0 && i < choices.size()) {
                    picked.put(i, choices.get(i));
                }
            }
        } else if (value != null && value.isJsonPrimitive()) {
            final int i = value.getAsInt();
            if (i >= 0 && i < choices.size()) {
                picked.put(i, choices.get(i));
            }
        }
        return new ArrayList<>(picked.values());
    }

    private static JsonObject question(final String message) {
        final JsonObject o = new JsonObject();
        o.addProperty("message", message);
        return o;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final JsonObject o = question(question);
        o.add("card", JsonView.id(c));
        o.add("options", options(options, null));
        return ask("confirm", o, new JsonPrimitive(defaultIsYes)).getAsBoolean();
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText,
            final String noButtonText, final boolean defaultYes) {
        final JsonObject o = question(message);
        o.addProperty("title", title);
        o.add("options", options(List.of(yesButtonText, noButtonText), null));
        return ask("confirm", o, new JsonPrimitive(defaultYes)).getAsBoolean();
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options,
            final int defaultOption) {
        final JsonObject o = question(message);
        o.addProperty("title", title);
        o.add("options", options(options, null));
        return ask("option", o, new JsonPrimitive(defaultOption)).getAsInt();
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput,
            final List<String> inputOptions, final boolean isNumeric) {
        final JsonObject o = question(message);
        o.addProperty("title", title);
        o.addProperty("numeric", isNumeric);
        o.add("options", options(inputOptions, null));
        final String first = inputOptions != null && !inputOptions.isEmpty() ? inputOptions.get(0) : initialInput;
        final JsonElement v = ask("text", o, first == null ? JsonNull.INSTANCE : new JsonPrimitive(first));
        return v.isJsonNull() ? null : v.getAsString();
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices,
            final List<T> selected, final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        final JsonObject o = question(message);
        o.addProperty("min", min);
        o.addProperty("max", max);
        o.add("options", options(choices, display));
        final int n = Math.max(0, Math.min(min, choices.size()));
        return pick(choices, ask("choose", o, indexes(0, n)));
    }

    @Override
    public <T> OrderResult<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard,
            final boolean sideboardingMode, final boolean showRememberCheckbox) {
        final JsonObject o = question(title);
        o.addProperty("top", top);
        o.addProperty("leave_min", remainingObjectsMin);
        o.addProperty("leave_max", remainingObjectsMax);
        o.add("options", options(sourceChoices, null));
        o.add("card", JsonView.id(referenceCard));
        // the default: everything, in the order given (leaving behind what must be left)
        final int take = Math.max(0, sourceChoices.size() - Math.max(0, remainingObjectsMin));
        return new OrderResult<>(pick(sourceChoices, ask("order", o, indexes(0, take))), false);
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        return null;        // one game a match: nothing to swap
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
            final DelayedReveal delayedReveal, final boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        final JsonObject o = question(title);
        o.addProperty("min", isOptional ? 0 : 1);
        o.addProperty("max", 1);
        o.add("options", options(optionList, null));
        final List<? extends GameEntityView> got = pick(optionList, ask("choose", o, indexes(0, isOptional ? 0 : 1)));
        return got.isEmpty() ? null : got.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList,
            final int min, final int max, final DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) {
            return new ArrayList<>();
        }
        final JsonObject o = question(title);
        o.addProperty("min", min);
        o.addProperty("max", max);
        o.add("options", options(optionList, null));
        final int n = Math.max(0, Math.min(min, optionList.size()));
        return new ArrayList<>(pick(optionList, ask("choose", o, indexes(0, n))));
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
            final Iterable<CardView> manipulable, final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> all = new ArrayList<>();
        for (final CardView c : cards) {
            all.add(c);
        }
        final JsonObject o = question(title);
        o.add("options", options(all, null));
        o.add("movable", JsonView.ids(manipulable));
        o.addProperty("to_top", toTop);
        o.addProperty("to_bottom", toBottom);
        o.addProperty("to_anywhere", toAnywhere);
        final List<CardView> got = pick(all, ask("arrange", o, indexes(0, all.size())));
        return got.size() == all.size() ? got : all;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities,
            final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        final JsonObject o = question(String.valueOf(hostCard));
        o.add("card", JsonView.id(hostCard));
        o.add("options", options(abilities, null));
        o.addProperty("min", 0);
        o.addProperty("max", 1);
        final List<SpellAbilityView> got = pick(abilities, ask("ability", o, new JsonArray()));
        return got.isEmpty() ? null : got.get(0);
    }

    /** Not asked yet (M3): the damage goes to the first blocker, or to what was attacked. */
    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
            final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final JsonObject o = question("Combat damage");
        o.add("card", JsonView.id(attacker));
        o.add("options", options(blockers, null));
        o.addProperty("amount", damage);
        o.add("defender", JsonView.entity(defender));
        o.addProperty("may_skip", maySkip);
        tell("damage", o);
        if (maySkip) {
            return null;
        }
        final Map<CardView, Integer> map = new LinkedHashMap<>();
        map.put(blockers == null || blockers.isEmpty() ? null : blockers.get(0), damage);
        return map;
    }

    /** Not asked yet (M3): everything goes to the first target. */
    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
            final int amount, final boolean atLeastOne, final String amountLabel) {
        final JsonObject o = question(amountLabel);
        o.add("card", JsonView.id(effectSource));
        o.add("options", options(new ArrayList<>(target.keySet()), null));
        o.addProperty("amount", amount);
        o.addProperty("at_least_one", atLeastOne);
        tell("amount", o);
        final Map<Object, Integer> map = new LinkedHashMap<>();
        int left = amount;
        for (final Object key : target.keySet()) {
            final int n = map.isEmpty() ? left - (atLeastOne ? target.size() - 1 : 0) : atLeastOne ? 1 : 0;
            map.put(key, Math.max(0, n));
            left -= n;
        }
        return map;
    }

    // ================================================================== things only said

    @Override
    public void message(final String message, final String title) {
        final JsonObject o = msg("log");
        o.addProperty("kind", "MESSAGE");
        o.addProperty("title", title);
        o.addProperty("text", message);
        wire.send(o);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        error(title == null || title.isEmpty() ? message : title + ": " + message);
    }

    @Override
    public void flashIncorrectAction() {
        final JsonObject o = msg("log");
        o.addProperty("kind", "REFUSED");
        o.addProperty("text", "That can't be done now.");
        wire.send(o);
    }

    // ================================================================== nothing to do here

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
    }

    @Override
    public void showCombat() {
    }

    @Override
    public void alertUser() {
    }

    @Override
    public void finishGame() {
    }

    @Override
    public void setPanelSelection(final CardView hostCard) {
    }

    @Override
    public void setCard(final CardView card) {
    }

    @Override
    public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) {
    }

    /** Watching fast: don't stop at each phase (Forge's playback waits a moment at those it stops at). */
    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return !interactive && wantedSpeed == PlaybackSpeed.FAST;
    }
}
