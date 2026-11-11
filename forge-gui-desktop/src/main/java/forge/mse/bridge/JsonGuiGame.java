package forge.mse.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
import forge.game.GameEntity;
import forge.game.card.CardView;
import forge.game.combat.Combat;
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
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputChooseStartingHand;
import forge.gamemodes.match.input.InputConfirm;
import forge.gamemodes.match.input.InputConfirmMulligan;
import forge.gamemodes.match.input.InputLondonMulligan;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectManyBase;
import forge.gamemodes.match.input.InputSelectStackItems;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.gui.control.PlaybackSpeed;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import forge.util.Localizer;

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
 * Watching a game between AI players, {@link #interactive} is false: every question is sent as
 * a `prompt` and answered at once with its default. With a person at the table (BridgeMain
 * --human) it is true, and there are two kinds of `prompt` that wait:
 * <ul>
 * <li><b>reply "click"</b> – one of Forge's Input* classes is waiting (priority, paying mana,
 * targets, attackers, blockers, picking cards, a yes / no, the mulligan). The table answers with
 * `ok`, `cancel`, `card`, `player`, `stack`, `mana`. These prompts are put together on Swing's
 * thread by {@link #flushPrompt} from what Forge told the window (the message, the buttons, the
 * selection, the cards worth clicking) once the Input has shown itself.</li>
 * <li><b>reply "answer"</b> – a dialog ({@link #ask}: choose from a list, order, a number,
 * combat damage…). The thread that asked waits for the table's `answer`.</li>
 * </ul>
 */
public class JsonGuiGame extends AbstractGuiGame {
    public static final int PROTOCOL = 1;

    private final BridgeWire wire;
    private final long seed;
    private final PlaybackSpeed wantedSpeed;
    private volatile boolean interactive = false;

    private volatile Game game;
    private volatile PlayerControllerHuman human;       // the person's seat (null while only watching)
    private volatile Runnable onGameOver = () -> { };
    private volatile boolean autoPay = false;
    private volatile Set<PhaseType> stopsMine = null;   // null: Forge's own settings
    private volatile Set<PhaseType> stopsTheirs = null;
    private JsonView view = new JsonView(null);
    private int logSent = 0;
    private String lastState = "";
    private volatile boolean dirty = true;
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
    private List<Object> selectionThings = List.of();     // the selection's cards and chosen ones (for `shown`)
    // a person plays: which Input the message and the buttons were shown for, and what was last sent
    private Input messageFor = null;
    private Input buttonsFor = null;
    private boolean okEnabled = false;
    private String okLabel = "";
    private Input shownInput = null;
    private boolean shownOnce = false;
    private int promptRev = 0;
    private String lastPrompt = "";
    private int autoPresses = 0;
    private List<CardView> weak = List.of();
    private List<CardView> revealed = List.of();
    private int clicks = 0;                     // the table's clicks handled so far (Swing's thread)
    private volatile boolean resend = false;
    private final AtomicInteger settling = new AtomicInteger();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final Map<Integer, AbilityChoice> abilityPrompts = new ConcurrentHashMap<>();
    private final Map<Input, Integer> inputIds = new java.util.WeakHashMap<>();     // under promptLock
    // game threads that are waiting for the table (a click or an answer), each with "still waiting?"
    private final Map<Thread, java.util.function.BooleanSupplier> waiters = new ConcurrentHashMap<>();
    private final AtomicBoolean recheckQueued = new AtomicBoolean();
    private Input unshown = null;
    private int unshownTries = 0;
    private static final java.util.concurrent.ThreadPoolExecutor GAME_THREADS = gameThreads();
    private final Map<Input, Integer> inputRevs = new java.util.WeakHashMap<>();
    // MSE Play's testing tools, and catching up with a kept game
    private final BridgeOptions options;
    private final DevTools dev;
    private volatile boolean showAll = false;
    private volatile boolean aiLog = false;
    private volatile boolean quiet = false;         // catching up: no `state` and `cards` messages
    private int quietStates = 0;
    private PlayerView viewer = null;
    private PlaybackSpeed speedNow = PlaybackSpeed.NORMAL;

    private record AbilityChoice(int card, int count) { }

    public JsonGuiGame(final BridgeWire wire0, final long seed0, final PlaybackSpeed speed) {
        this(wire0, seed0, speed, new BridgeOptions());
    }

    public JsonGuiGame(final BridgeWire wire0, final long seed0, final PlaybackSpeed speed, final BridgeOptions options0) {
        wire = wire0;
        seed = seed0;
        wantedSpeed = speed;
        options = options0;
        dev = new DevTools(this, wire0, options0);
        showAll = options0.showOpponentHand;
        aiLog = options0.aiLog;
        quiet = options0.replayFast;
        wire.setListener(this::onMessage);
        wire.setOnConnect(() -> resync = true);
    }

    /** True once a person plays through this window: questions then wait for the table's answer. */
    public void setInteractive(final boolean interactive0) {
        interactive = interactive0;
    }

    public boolean isInteractive() {
        return interactive;
    }

    public boolean isGameOverSent() {
        return gameOverSent;
    }

    /** Run once `game_over` has been sent (a person's game: nobody clicks Forge's own "quit"). */
    public void setOnGameOver(final Runnable r) {
        onGameOver = r == null ? () -> { } : r;
    }

    /** Auto-pay: when a cost can be paid with Forge's "Auto" button, the bridge presses it itself. */
    public void setAutoPay(final boolean on) {
        autoPay = on;
    }

    /**
     * The phases at which the person is asked what to do while nothing is on the stack: on their
     * own turn and on the others' turns. null = Forge's own settings (PHASE_HUMAN_*, PHASE_AI_*).
     */
    public void setStops(final Set<PhaseType> mine, final Set<PhaseType> theirs) {
        stopsMine = mine == null ? null : Collections.unmodifiableSet(EnumSet.copyOf(withNone(mine)));
        stopsTheirs = theirs == null ? null : Collections.unmodifiableSet(EnumSet.copyOf(withNone(theirs)));
    }

    private static Set<PhaseType> withNone(final Set<PhaseType> set) {
        return set.isEmpty() ? EnumSet.noneOf(PhaseType.class) : set;
    }

    // the phases Forge's own window has a stop for, in the order of FPref.PHASES_HUMAN / PHASES_AI
    private static final PhaseType[] STOP_PHASES = { PhaseType.UPKEEP, PhaseType.DRAW, PhaseType.MAIN1,
            PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END, PhaseType.MAIN2,
            PhaseType.END_OF_TURN, PhaseType.CLEANUP };

    private static Set<PhaseType> stopsFromPrefs(final FPref[] prefs) {
        final Set<PhaseType> set = EnumSet.noneOf(PhaseType.class);
        for (int i = 0; i < STOP_PHASES.length && i < prefs.length; i++) {
            if (FModel.getPreferences().getPrefBoolean(prefs[i])) {
                set.add(STOP_PHASES[i]);
            }
        }
        return set;
    }

    private Set<PhaseType> stops(final boolean mine) {
        final Set<PhaseType> set = mine ? stopsMine : stopsTheirs;
        return set != null ? set : stopsFromPrefs(mine ? FPref.PHASES_HUMAN : FPref.PHASES_AI);
    }

    private static JsonArray names(final Set<PhaseType> phases) {
        final JsonArray a = new JsonArray();
        for (final PhaseType p : phases) {
            a.add(p.name());
        }
        return a;
    }

    /** "MAIN1,MAIN2" → the phases (Forge's names; "all" and "none" too). Unknown names are an error. */
    public static Set<PhaseType> parsePhases(final String text) {
        final Set<PhaseType> set = EnumSet.noneOf(PhaseType.class);
        final String t = text == null ? "" : text.trim();
        if (t.equalsIgnoreCase("all")) {
            Collections.addAll(set, STOP_PHASES);
        } else if (!t.isEmpty() && !t.equalsIgnoreCase("none")) {
            for (final String name : t.split("[,\\s]+")) {
                if (!name.isEmpty()) {
                    set.add(PhaseType.valueOf(name.toUpperCase()));
                }
            }
        }
        return set;
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
        viewer = me;
        view = new JsonView(me, showAll);
        dev.setGame(g);
        if (options.handSize >= 0) {        // Forge takes the opening hand's size for the most cards a hand may hold too
            for (final Player p : g.getPlayers()) {
                p.setMaxHandSize(7);
            }
        }
        BridgeAi.setLog(this::aiSays);
        BridgeAi.setPause(this::pauseBeforeAi);
        logSent = 0;
        lastState = "";
        gameOverSent = false;
        human = null;
        if (interactive && getGameController() instanceof PlayerControllerHuman h) {
            human = h;
            // told whenever an Input starts or ends waiting, on whichever thread does it
            h.getInputQueue().addObserver((o, arg) -> inputChanged());
        }
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
            if (p.getLobbyPlayer() instanceof BridgeAi ai) {
                po.addProperty("ai_profile", ai.getAiProfile());
                po.addProperty("ai_mode", ai.getMode().name().toLowerCase());
            }
            players.add(po);
        }
        o.add("players", players);
        final JsonArray zones = new JsonArray();
        for (final String z : JsonView.ZONE_NAMES) {
            zones.add(z);
        }
        o.add("zones", zones);
        // testing: what this game was started with, and what `dev` can do (left to the table to show)
        o.addProperty("testing", options.testing);
        o.add("options", options.toJson());
        if (options.testing) {
            final JsonArray ops = new JsonArray();
            for (final String op : DevTools.OPS) {
                ops.add(op);
            }
            o.add("dev_ops", ops);
        }
        if (interactive) {
            final JsonObject stops = new JsonObject();
            stops.add("mine", names(stops(true)));
            stops.add("theirs", names(stops(false)));
            o.add("stops", stops);
            o.addProperty("auto_pay", autoPay);
        }
        wire.send(o);
    }

    @Subscribe
    public void onGameEvent(final GameEvent ev) {
        try {
            if (ev instanceof GameEventPlayerPriority) {
                dev.atPriority();       // the game is at rest: the testing tools' turn
            }
            dev.onEvent(ev);
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
                quiet = false;      // the end of the game is always shown, also to a table that was catching up
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

    /**
     * The whole game, if anything changed since the last one sent. On the game thread – or, with a
     * person playing, on Swing's thread while the game thread waits for their click.
     */
    private synchronized void sendState() {
        final GameView gv = getGameView();
        if (gv == null || !dirty && !resync) {
            return;
        }
        if (resync) {           // a table connected in the middle: start it from the beginning
            resync = false;
            lastState = "";
            sendHello();
            if (interactive) {
                resend = true;
                promptChanged();
            } else {
                showPrompt(false);
            }
        }
        if (quiet && (options.replayStates <= 0 || ++quietStates % options.replayStates != 0)) {
            return;         // catching up: the table isn't drawing
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
        if (quiet) {
            return;
        }
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
        releaseQuestions();
        onGameOver.run();
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
        final PlaybackSpeed wanted = quiet ? PlaybackSpeed.FAST : wantedSpeed;
        for (PlaybackSpeed s = speedNow; s != wanted; s = s.nextSpeed()) {
            c.selectButtonCancel();
        }
        speedNow = wanted;
    }

    // ================================================================== testing tools

    PlayerControllerHuman human() {
        return human;
    }

    /** True when something is to be set up before the first turn (BridgeMain gives Forge the hook for it). */
    public boolean hasStart() {
        return dev.hasStart();
    }

    /** Forge's start hook, on the game thread. */
    public void applyStart() {
        dev.applyStart();
    }

    /** The testing tools changed the game: the next `state` has it. */
    void devChanged() {
        dirty = true;
    }

    /** Every hand is shown to the table (a testing switch) – or only what the person may look at again. */
    void setShowAll(final boolean on) {
        showAll = on;
        view = new JsonView(viewer, on);
        dirty = true;
    }

    void setAiLog(final boolean on) {
        aiLog = on;
    }

    /** What the AI decided, as a `log` line of kind AI (BridgeAi tells it on the game thread). */
    private void aiSays(final Player p, final String text) {
        if (!aiLog) {
            return;
        }
        final JsonObject o = msg("log");
        o.addProperty("kind", "AI");
        o.addProperty("player", p.getId());
        o.addProperty("text", text);
        wire.send(o);
    }

    /** "Pause before the AI casts a spell": a question that waits for any answer (only when somebody plays). */
    private boolean pauseBeforeAi(final forge.game.spellability.SpellAbility sa) {
        if (!dev.pausesBeforeAi() || !interactive || sa.getHostCard() == null) {
            return false;
        }
        final JsonObject o = question(sa.getActivatingPlayer() + " is about to " + (sa.isSpell() ? "cast " : "use ")
                + sa.getHostCard() + ".");
        o.addProperty("title", "Paused");
        o.addProperty("pause", true);
        o.addProperty("card", sa.getHostCard().getId());
        final JsonArray labels = new JsonArray();
        labels.add("Continue");
        o.add("options", labels);
        ask("confirm", o, new JsonPrimitive(true));
        return true;
    }

    /**
     * `dev` from the table (the reader's thread). A message with `at` waits for its moment; any other
     * is done as soon as the game is at rest – at once when the person is being asked what to do
     * (that prompt is ended without an action, as Forge's undo does, and asked again afterwards).
     */
    private void dev(final JsonObject m) {
        if (!m.has("op") && "export_state".equals(m.get("t").getAsString())) {
            m.addProperty("op", "export_state");
        }
        if (m.has("at") || !interactive) {
            if (dev.onDev(m) && !m.has("at")) {
                dev.saidQueued(m);
            }
            return;
        }
        forge.gui.FThreads.invokeInEdtLater(() -> {
            final Input in = currentInput();
            final boolean kick = in instanceof InputPassPriority && !gameOverSent;
            m.addProperty("kicked", kick);
            if (!dev.onDev(m)) {
                return;
            }
            if (kick) {
                ((InputPassPriority) in).restartPrompt();
            } else {
                dev.saidQueued(m);
            }
        });
    }

    /** The table has caught up with a kept game (`replay_done`), or a watched one reached --replay-until. */
    void caughtUp() {
        if (!quiet) {
            return;
        }
        quiet = false;
        dirty = true;
        final JsonObject o = msg("log");
        o.addProperty("kind", "DEV");
        o.addProperty("text", "Caught up.");
        o.addProperty("caught_up", true);
        wire.send(o);
        if (interactive) {
            if (wantedSpeed != PlaybackSpeed.FAST) {
                FModel.getPreferences().setPref(FPref.YIELD_SKIP_PHASE_DELAY, false);
                FModel.getPreferences().setPref(FPref.YIELD_SKIP_RESOLVE_DELAY, false);
            }
            resend = true;
            inputChanged();
        } else {
            final IGameController c = getGameController();
            if (c != null && speedSet) {
                setSpeed();
                if (options.replayPause) {
                    c.selectButtonOk();
                }
            }
        }
    }

    /**
     * The game thread is about to wait for the player's click (an Input*): send what the game
     * looks like now, then wait. The prompt itself comes through showPromptMessage and
     * updateButtons on the event thread, so it may arrive just before or just after this state.
     */
    @Override
    public void awaitInput(final CountDownLatch done) throws InterruptedException {
        final Thread me = Thread.currentThread();
        waiters.put(me, () -> done.getCount() > 0);     // from here on this thread changes nothing in the game
        try {
            dirty = true;
            try {
                sendState();
            } catch (final RuntimeException e) {
                error("The bridge couldn't describe the game: " + e);
            }
            done.await();
        } finally {
            waiters.remove(me);
        }
    }

    /** Forge's pool of game threads (private in ThreadUtil): how many of them are at work is all that's read. */
    private static java.util.concurrent.ThreadPoolExecutor gameThreads() {
        try {
            final java.lang.reflect.Field f = forge.util.ThreadUtil.class.getDeclaredField("gameThreadPool");
            f.setAccessible(true);
            return f.get(null) instanceof java.util.concurrent.ThreadPoolExecutor pool ? pool : null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            System.err.println("MSE-BRIDGE can't see Forge's game threads (" + e + "): prompts won't say `busy`");
            return null;
        }
    }

    /**
     * True while a game thread is at work rather than waiting for the table. Forge's Inputs take a
     * click while the one before is still being carried out on another game thread (its own window
     * relies on people being slower than that); a table should wait until this is false.
     */
    private boolean gameBusy() {
        final java.util.concurrent.ThreadPoolExecutor pool = GAME_THREADS;
        if (pool == null) {
            return false;
        }
        int waiting = 0;
        for (final Map.Entry<Thread, java.util.function.BooleanSupplier> e : waiters.entrySet()) {
            if (e.getValue().getAsBoolean() && e.getKey().getName().startsWith("Game")) {
                waiting++;
            }
        }
        return pool.getActiveCount() > waiting;
    }

    // ================================================================== what the table sends

    private void onMessage(final JsonObject m) {
        final String t = m.get("t").getAsString();
        switch (t) {
            case "answer":
                answer(m);
                break;
            case "ok":          // the prompt's first button (watching: pause / resume)
                click(m, IGameController::selectButtonOk);
                break;
            case "cancel":      // its second button (watching: the next speed, or one step while paused)
                click(m, IGameController::selectButtonCancel);
                break;
            case "card":
                click(m, c -> {
                    final int id = m.get("id").getAsInt();
                    final CardView card = findCard(id);
                    if (card == null) {
                        refused("That card isn't there any more.", id, m);
                    } else if (!c.selectCard(card, null, null)) {
                        refused("That card can't be chosen now.", id, m);
                    }
                });
                break;
            case "player":
                click(m, c -> c.selectPlayer(playerById(m.get("id").getAsInt()), null));
                break;
            case "stack":
                click(m, c -> c.selectStackItem(stackItemById(m.get("id").getAsInt())));
                break;
            case "mana":
                click(m, c -> c.useMana(colour(m.get("colour").getAsString())));
                break;
            case "ability":     // {card, index}: the same as answering the `ability` prompt of that card
                ability(m);
                break;
            case "undo":
                click(m, IGameController::undoLastAction);
                break;
            case "concede":
                click(m, IGameController::concede);
                break;
            case "auto_pay":
                autoPay = !m.has("on") || m.get("on").getAsBoolean();
                resend = true;
                promptChanged();
                break;
            case "stops":
                try {
                    setStops(m.has("mine") ? phases(m.get("mine")) : stopsMine,
                            m.has("theirs") ? phases(m.get("theirs")) : stopsTheirs);
                } catch (final RuntimeException e) {
                    error("Those aren't phases: " + e.getMessage());
                }
                break;
            case "dev":             // a testing tool {op, …}: answered with dev_result
            case "export_state":    // {path?}: the position as a `scenario` message
                dev(m);
                break;
            case "replay_done":     // the table has caught up (--replay-fast): states are sent again
                caughtUp();
                break;
            case "quit":
                BridgeMain.finish(0);
                break;
            default:
                error("The bridge doesn't know the message \"" + t + "\"");
        }
    }

    private static Set<PhaseType> phases(final JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;        // back to Forge's own
        }
        if (e.isJsonPrimitive()) {
            return parsePhases(e.getAsString());
        }
        final Set<PhaseType> set = EnumSet.noneOf(PhaseType.class);
        for (final JsonElement x : e.getAsJsonArray()) {
            set.add(PhaseType.valueOf(x.getAsString().toUpperCase()));
        }
        return set;
    }

    private static byte colour(final String name) {
        final String n = name.trim();
        if (n.equalsIgnoreCase("C") || n.equalsIgnoreCase("colourless") || n.equalsIgnoreCase("colorless")) {
            return (byte) forge.card.mana.ManaAtom.COLORLESS;
        }
        final byte b = forge.card.MagicColor.fromName(n);
        if (b == 0) {
            throw new IllegalArgumentException("no colour " + name);
        }
        return b;
    }

    /** `answer` {prompt, value}: for a question that waits – or, true / false, the buttons of the prompt showing. */
    private void answer(final JsonObject m) {
        final int id = m.get("prompt").getAsInt();
        final JsonElement value = m.has("value") ? m.get("value") : JsonNull.INSTANCE;
        final CompletableFuture<JsonElement> f = waiting.remove(id);
        if (f != null) {
            abilityPrompts.remove(id);
            f.complete(value);
        } else if (interactive && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            click(m, value.getAsBoolean() ? IGameController::selectButtonOk : IGameController::selectButtonCancel);
        } else {
            error("No question is waiting for answer " + id);
        }
    }

    private void ability(final JsonObject m) {
        final int card = m.get("card").getAsInt();
        for (final Map.Entry<Integer, AbilityChoice> e : abilityPrompts.entrySet()) {
            if (e.getValue().card() == card) {
                final CompletableFuture<JsonElement> f = waiting.remove(e.getKey());
                abilityPrompts.remove(e.getKey());
                if (f != null) {
                    final JsonArray a = new JsonArray();
                    if (m.has("index") && !m.get("index").isJsonNull()) {
                        a.add(m.get("index").getAsInt());
                    }
                    f.complete(a);
                    return;
                }
            }
        }
        error("No choice of abilities is waiting for card " + card);
    }

    /**
     * The player's actions go to Forge on the event thread, as its own window's clicks do. A click
     * that names a `prompt` which isn't the one waiting any more is dropped (log STALE). Every
     * click is counted, and the prompt is sent again afterwards with that count (`clicks`), so a
     * table knows which of its clicks a prompt already takes into account.
     */
    private void click(final JsonObject m, final java.util.function.Consumer<IGameController> action) {
        forge.gui.FThreads.invokeInEdtLater(() -> {
            final IGameController c = getGameController();
            try {
                if (c == null) {
                    error("No game is running");
                } else if (stale(m)) {
                    final JsonObject o = msg("log");
                    o.addProperty("kind", "STALE");
                    o.addProperty("text", "That prompt isn't waiting any more.");
                    o.add("prompt", m.get("prompt"));
                    wire.send(o);
                } else {
                    action.accept(c);
                }
            } catch (final RuntimeException e) {
                error("Forge refused that: " + e);
            } finally {
                if (interactive) {
                    synchronized (promptLock) {
                        clicks++;
                    }
                    dirty = true;
                    resend = true;
                    inputChanged();     // two steps later: once what the click started has shown itself
                }
            }
        });
    }

    private boolean stale(final JsonObject m) {
        final PlayerControllerHuman h = human;
        if (h == null || !m.has("prompt") || m.get("prompt").isJsonNull() || "answer".equals(m.get("t").getAsString())) {
            return false;
        }
        final int id = m.get("prompt").getAsInt();
        synchronized (promptLock) {
            return h.getInputQueue().getInput() != shownInput || id != inputPromptId;
        }
    }

    private void refused(final String text, final Integer card, final JsonObject click) {
        final JsonObject o = msg("log");
        o.addProperty("kind", "REFUSED");
        o.addProperty("text", text);
        if (card != null) {
            o.addProperty("card", card);
        }
        if (click.has("prompt")) {
            o.add("prompt", click.get("prompt"));
        }
        wire.send(o);
    }

    private StackItemView stackItemById(final int id) {
        final GameView gv = getGameView();
        if (gv != null && gv.getStack() != null) {
            for (final StackItemView s : gv.getStack()) {
                if (s.getId() == id) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("nothing on the stack has id " + id);
    }

    private CardView findCard(final int id) {
        final GameView gv = getGameView();
        if (gv != null) {
            for (final PlayerView p : gv.getPlayers()) {
                for (final forge.game.zone.ZoneType z : forge.game.zone.ZoneType.values()) {
                    final Iterable<CardView> cards = p.getCards(z);
                    if (cards != null) {
                        for (final CardView c : cards) {
                            if (c.getId() == id) {
                                return c;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The id of every card that is in a zone now. */
    private Set<Integer> cardIds() {
        final Set<Integer> ids = new java.util.HashSet<>();
        final GameView gv = getGameView();
        if (gv != null) {
            for (final PlayerView p : gv.getPlayers()) {
                for (final forge.game.zone.ZoneType z : forge.game.zone.ZoneType.values()) {
                    final Iterable<CardView> cards = p.getCards(z);
                    if (cards != null) {
                        for (final CardView c : cards) {
                            ids.add(c.getId());
                        }
                    }
                }
            }
        }
        return ids;
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

    /** Watching: the line under the table and its two buttons (kind `watch`), sent as Forge changes them. */
    private void showPrompt(final boolean isNew) {
        final JsonObject o = msg("prompt");
        synchronized (promptLock) {
            if (isNew) {
                inputPromptId = promptIds.incrementAndGet();
            }
            o.addProperty("id", inputPromptId);
            o.addProperty("kind", "watch");
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

    /** Forge changed what its prompt shows. Watching: sent at once. A person playing: gathered by flushPrompt. */
    private void promptChanged() {
        if (interactive && flushQueued.compareAndSet(false, true)) {
            forge.gui.FThreads.invokeInEdtLater(this::flushPrompt);
        }
    }

    /**
     * An Input started or stopped waiting (any thread). Forge shows the new one in a task it has just
     * put on Swing's queue, before or after this call – so the prompt is sent two steps later, when
     * that task has certainly run.
     */
    private void inputChanged() {
        settling.incrementAndGet();
        forge.gui.FThreads.invokeInEdtLater(() -> forge.gui.FThreads.invokeInEdtLater(() -> {
            settling.decrementAndGet();
            flushPrompt();
        }));
    }

    private Input currentInput() {
        final PlayerControllerHuman h = human;
        return h == null ? null : h.getInputQueue().getInput();
    }

    /** Forge's name for the Input (of the class it was made from, when it's a nameless one). */
    private static String className(final Input in) {
        Class<?> c = in.getClass();
        while (c.getSimpleName().isEmpty() && c.getSuperclass() != null) {
            c = c.getSuperclass();
        }
        return c.getSimpleName();
    }

    private static String kindOf(final Input in) {
        if (in instanceof InputPassPriority) {
            return "priority";
        } else if (in instanceof InputPayMana) {
            return "mana";
        } else if (in instanceof InputSelectTargets || in instanceof InputSelectStackItems) {
            return "targets";
        } else if (in instanceof InputAttack) {
            return "attack";
        } else if (in instanceof InputBlock) {
            return "block";
        } else if (in instanceof InputConfirmMulligan || in instanceof InputLondonMulligan
                || in instanceof InputChooseStartingHand) {
            return "mulligan";
        } else if (in instanceof InputConfirm) {
            return "confirm";
        } else if (in instanceof InputSelectManyBase) {
            return "select";
        }
        return in == null ? "wait" : "input";
    }

    /**
     * A person plays: the prompt that's showing, as one message. Swing's thread only. While an Input
     * waits it is `wait: true, reply: "click"`; between Inputs (the game is working, or it's
     * somebody else's move) it is kind `wait` with `wait: false`.
     */
    private void flushPrompt() {
        flushQueued.set(false);
        final PlayerControllerHuman h = human;
        if (h == null || gameOverSent || settling.get() > 0) {
            return;
        }
        try {
            final Input in = h.getInputQueue().getInput();
            if (in instanceof InputPassPriority again && dev.takeRecheck()) {
                // the testing tools changed the game just before this prompt: Forge is asked to look at
                // the game again (state-based actions, triggers) and then asks afresh
                again.restartPrompt();
                return;
            }
            final JsonObject o = msg("prompt");
            boolean press = false;
            boolean busy = false;
            synchronized (promptLock) {
                if (in != null && messageFor != in && buttonsFor != in) {
                    // It hasn't shown itself yet: its message and buttons are on their way. Should they not
                    // come (Forge's showMessage can fail while a game thread is still at work), it is asked again.
                    if (unshown != in) {
                        unshown = in;
                        unshownTries = 0;
                    }
                    if (unshownTries < 3 && recheckQueued.compareAndSet(false, true)) {
                        forge.gui.FThreads.delayInEDT(300, () -> {
                            recheckQueued.set(false);
                            final boolean still;
                            synchronized (promptLock) {
                                still = currentInput() == in && messageFor != in && buttonsFor != in;
                                if (still) {
                                    unshownTries++;
                                }
                            }
                            if (still) {
                                try {
                                    in.showMessageInitial();
                                } catch (final RuntimeException e) {
                                    error("Forge couldn't show its prompt: " + e);
                                }
                            }
                            flushPrompt();
                        });
                    }
                    return;
                }
                if (in != shownInput || !shownOnce) {
                    shownInput = in;
                    shownOnce = true;
                    // between Inputs it is always prompt 0: how often that one is sent depends on timing,
                    // and the ids of the prompts that wait shouldn't
                    // An Input keeps its id when another one (paying for a mana ability, say) was on top of it for a while.
                    inputPromptId = in == null ? 0 : inputIds.computeIfAbsent(in, x -> promptIds.incrementAndGet());
                    promptRev = in == null ? 0 : inputRevs.getOrDefault(in, 0);
                    autoPresses = 0;
                }
                final String kind = kindOf(in);
                busy = in != null && (gameBusy() || in instanceof InputPayMana pay && pay.isActivatingManaAbility());
                final boolean ok = buttonsFor == in && okEnabled;
                press = autoPay && in instanceof InputPayMana && !busy && ok && autoPresses < 5
                        && okLabel.equals(Localizer.getInstance().getMessage("lblAuto"));
                o.addProperty("id", inputPromptId);
                o.addProperty("kind", kind);
                o.addProperty("wait", in != null && !busy && !press);
                o.addProperty("reply", "click");
                o.addProperty("input", in == null ? null : className(in));
                o.add("player", in == null ? JsonView.id(getCurrentPlayer()) : JsonView.id(in.getOwner()));
                o.addProperty("message", messageFor == in ? promptMessage : "");
                o.add("card", messageFor == in ? promptCard : JsonNull.INSTANCE);
                o.add("buttons", buttonsFor == in ? promptButtons : buttons("", "", false, false, true));
                o.add("selection", promptSelection != null ? promptSelection : selection(Selection.NONE));
                // the cards worth a click: what can be played, tapped for mana, attack or block – and,
                // while paying, the ones Forge's Auto button would tap
                final JsonArray playable = new JsonArray();
                final JsonArray auto = new JsonArray();
                final Set<Integer> seen = new java.util.HashSet<>();
                final Set<Integer> there = weak.isEmpty() ? Set.of() : cardIds();
                for (final CardView c : weak) {
                    if (!there.contains(c.getId())) {
                        continue;       // gone since Forge worked out its plan (a token that was sacrificed for mana)
                    }
                    if (seen.add(c.getId())) {
                        playable.add(c.getId());
                    } else if (!auto.contains(new JsonPrimitive(c.getId()))) {
                        auto.add(c.getId());
                    }
                }
                if (in instanceof InputLondonMulligan && human.getPlayer() != null) {
                    for (final CardView c : human.getPlayer().getView().getHand()) {
                        playable.add(c.getId());
                    }
                }
                o.add("playable", playable);
                o.addProperty("busy", busy);
                if (in instanceof InputPayMana) {
                    o.add("auto", auto);
                    if (press) {
                        o.addProperty("auto_paying", true);
                    }
                }
                if (in instanceof InputAttack) {
                    o.add("defenders", defenders());
                }
                if (!revealed.isEmpty()) {
                    o.add("revealed", view.cards(revealed));
                }
                // the cards to choose from that the table can't see in the state (your library…)
                if (in != null) {
                    final List<Object> things = new ArrayList<>(selectionThings);
                    things.addAll(weak);
                    final JsonArray shown = view.shown(things);
                    if (!shown.isEmpty()) {
                        o.add("shown", shown);
                    }
                }
                if (busy && recheckQueued.compareAndSet(false, true)) {
                    // look again shortly: the prompt waits for the table once the game has caught up
                    forge.gui.FThreads.delayInEDT(15, () -> {
                        recheckQueued.set(false);
                        flushPrompt();
                    });
                }
                final String text = BridgeWire.toJson(o);
                if (text.equals(lastPrompt) && !(resend && !busy)) {
                    return;
                }
                lastPrompt = text;
                if (!busy) {
                    resend = false;
                }
                o.addProperty("clicks", clicks);
                o.addProperty("rev", ++promptRev);
                if (in != null) {
                    inputRevs.put(in, promptRev);
                }
                if (press) {
                    autoPresses++;
                }
            }
            if (in != null && !busy) {      // the game thread is waiting for this click: the table gets the game as it is
                dirty = true;
                sendState();
            }
            wire.send(o);
            if (press) {
                h.selectButtonOk();
            }
        } catch (final RuntimeException e) {
            error("The bridge couldn't describe the prompt: " + e);
        }
    }

    /** Who or what can be attacked (players, planeswalkers, battles) while attackers are declared. */
    private JsonArray defenders() {
        final JsonArray a = new JsonArray();
        final Game g = game;
        final Combat combat = g == null ? null : g.getCombat();
        if (combat != null) {
            for (final GameEntity e : combat.getDefenders()) {
                a.add(JsonView.entity(GameEntityView.get(e)));
            }
        }
        return a;
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        final Input in = interactive ? currentInput() : null;
        synchronized (promptLock) {
            promptPlayer = JsonView.id(playerView);
            promptMessage = message == null ? "" : message;
            promptCard = JsonView.id(card);
            messageFor = in;
        }
        if (interactive) {
            promptChanged();
        } else {
            showPrompt(true);
        }
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1,
            final boolean enable2, final boolean focus1) {
        final Input in = interactive ? currentInput() : null;
        synchronized (promptLock) {
            promptButtons = buttons(label1, label2, enable1, enable2, focus1);
            buttonsFor = in;
            okEnabled = enable1;
            okLabel = label1 == null ? "" : label1;
        }
        if (interactive) {
            promptChanged();
        } else {
            showPrompt(false);
        }
    }

    private static JsonObject selection(final Selection next) {
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
        return s;
    }

    /** What the player may click for the prompt that's showing (targets, attackers, cards to pay with…). */
    @Override
    public void setSelection(final Selection next) {
        super.setSelection(next);
        final JsonObject s = selection(next);
        final List<Object> things = new ArrayList<>(next.cards());
        things.addAll(next.chosen());
        synchronized (promptLock) {
            promptSelection = next == Selection.NONE ? null : s;
            selectionThings = things;
        }
        promptChanged();
    }

    /** The cards worth a click now (Forge's own window gives them a glow). */
    @Override
    public void setWeaklySelectable(final Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        final List<CardView> list = new ArrayList<>();
        for (final CardView c : cards) {
            list.add(c);
        }
        synchronized (promptLock) {
            weak = list;
        }
        promptChanged();
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        synchronized (promptLock) {
            weak = List.of();
        }
        promptChanged();
    }

    /** Cards shown to the player for the prompt that's waiting (`revealed` in it). */
    @Override
    public void showRevealedCards(final Iterable<CardView> cards) {
        final List<CardView> list = new ArrayList<>();
        for (final CardView c : cards) {
            list.add(c);
        }
        synchronized (promptLock) {
            revealed = list;
        }
        promptChanged();
    }

    @Override
    public void hideRevealedCards() {
        synchronized (promptLock) {
            revealed = List.of();
        }
        promptChanged();
    }

    /** Things on the stack can be clicked as targets (`stack` {id}) rather than chosen from a list. */
    @Override
    public boolean canPickStackTargets() {
        return interactive;
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
        body.addProperty("wait", interactive && !gameOverSent);
        body.addProperty("reply", "answer");
        body.add("default", fallback);
        if (!interactive || gameOverSent) {
            wire.send(body);
            return fallback;
        }
        final CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        final Thread me = Thread.currentThread();
        waiters.put(me, () -> !answer.isDone());
        try {       // whichever thread asks waits for the answer: show the table the game as it is now
            dirty = true;
            sendState();
        } catch (final RuntimeException e) {
            error("The bridge couldn't describe the game: " + e);
        }
        waiting.put(id, answer);
        if ("ability".equals(kind) && body.has("card") && body.get("card").isJsonPrimitive()) {
            abilityPrompts.put(id, new AbilityChoice(body.get("card").getAsInt(), body.getAsJsonArray("options").size()));
        }
        wire.send(body);
        try {
            final JsonElement value = answer.get();
            return value == null || value.isJsonNull() && !fallback.isJsonNull() ? fallback : value;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (final ExecutionException e) {
            return fallback;
        } finally {
            waiters.remove(me);
            waiting.remove(id);
            abilityPrompts.remove(id);
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

    /** `shown` for a question whose options are cards the table can't see in the state. */
    private JsonObject withShown(final JsonObject o, final Iterable<?> things, final DelayedReveal reveal) {
        final List<Object> all = new ArrayList<>();
        if (things != null) {
            for (final Object x : things) {
                all.add(x);
            }
        }
        if (reveal != null && reveal.getCards() != null) {
            all.addAll(reveal.getCards());
        }
        final JsonArray shown = view.shown(all);
        if (!shown.isEmpty()) {
            o.add("shown", shown);
        }
        return o;
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
        if (c != null) {
            withShown(o, List.of(c), null);
        }
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
        if (min < 0 && max < 0) {       // nothing to choose: Forge only shows these (AbstractGuiGame.reveal)
            o.add("options", options(choices, display));
            withShown(o, choices, null);
            ask("reveal", o, new JsonArray());
            return new ArrayList<>();
        }
        o.addProperty("min", min);
        o.addProperty("max", max);
        o.add("options", options(choices, display));
        withShown(o, choices, null);
        if (selected != null && !selected.isEmpty()) {
            final JsonArray chosen = new JsonArray();
            for (final T x : selected) {
                if (choices.contains(x)) {
                    chosen.add(choices.indexOf(x));
                }
            }
            o.add("selected", chosen);
        }
        final int n = Math.max(0, Math.min(min, choices.size()));
        final List<T> got = pick(choices, ask("choose", o, indexes(0, n)));
        if (got.size() < n || max >= 0 && got.size() > max) {       // not an answer Forge could take
            return pick(choices, indexes(0, n));
        }
        return got;
    }

    @Override
    public Integer getInteger(final String message, final int min, final int max, final boolean sortDesc) {
        return number(message, min, max);
    }

    @Override
    public Integer getInteger(final String message, final int min, final int max, final int cutoff) {
        return number(message, min, max);
    }

    /** A number from min to max (kind `number`); null = the player didn't want to say. */
    private Integer number(final String message, final int min, final int max) {
        if (max <= min) {
            return min;
        }
        final JsonObject o = question(message);
        o.addProperty("min", min);
        // X of a spell or ability being cast: the highest X that can be paid now, as Forge works it out
        // (every untapped mana source and the pool, the rest of the cost taken off, {X}{X}, colours)
        final Integer canPay = highestX(o);
        int most = max;
        if (canPay != null) {
            most = Math.max(min, Math.min(max, canPay));
            o.addProperty("max", most);
        } else if (max != Integer.MAX_VALUE) {
            o.addProperty("max", max);
        } else {
            o.add("max", JsonNull.INSTANCE);
        }
        o.addProperty("max_known", canPay != null || max != Integer.MAX_VALUE);
        final JsonElement v = ask("number", o, new JsonPrimitive(canPay != null ? most : min));
        try {
            return v.isJsonNull() ? null : Math.max(min, Math.min(max, v.getAsInt()));
        } catch (final RuntimeException e) {
            return min;
        }
    }

    /**
     * While the person announces X: adds `x: true`, `mana` (what they can make now: untapped sources and
     * the pool) and `x_mana` (mana each X costs: 2 for {X}{X}) to the prompt and returns the highest X
     * Forge can pay, or null when it isn't an X or Forge can't tell. Game thread (it waits for the answer).
     */
    private Integer highestX(final JsonObject o) {
        final PlayerControllerHuman h = human;
        final forge.game.spellability.SpellAbility sa = h == null ? null : h.getAnnouncingX();
        if (sa == null) {
            return null;
        }
        o.addProperty("x", true);
        try {
            final Player p = h.getPlayer();
            final forge.game.cost.Cost cost = sa.getRootAbility().getPayCosts();
            final forge.game.cost.CostPartMana part = cost == null ? null : cost.getCostMana();
            o.addProperty("x_mana", part == null ? 1 : Math.max(1, part.getMana().countX()));
            o.addProperty("mana", forge.ai.ComputerUtilMana.getAvailableManaEstimate(p, true)
                    + p.getManaPool().totalMana());
            return Math.max(0, forge.ai.ComputerUtilMana.determineLeftoverMana(sa, p, false));
        } catch (final RuntimeException e) {
            return null;
        }
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
        withShown(o, sourceChoices, null);
        o.add("card", JsonView.id(referenceCard));
        // the default: everything, in the order given (leaving behind what must be left)
        final int take = Math.max(0, sourceChoices.size() - Math.max(0, remainingObjectsMin));
        return new OrderResult<>(pick(sourceChoices, ask("order", o, indexes(0, take))), false);
    }

    /** Not asked (one game a match: nothing to swap between games): the deck stays as it is. */
    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        final JsonObject o = question(message);
        o.addProperty("main", main == null ? 0 : main.countAll());
        o.addProperty("sideboard", sideboard == null ? 0 : sideboard.countAll());
        tell("sideboard", o);
        return null;
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
        withShown(o, optionList, delayedReveal);
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
        withShown(o, optionList, delayedReveal);
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
        withShown(o, all, null);
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
        final JsonArray options = options(abilities, null);
        for (int i = 0; i < abilities.size(); i++) {
            options.get(i).getAsJsonObject().addProperty("can_play", abilities.get(i).canPlay());
        }
        o.add("options", options);
        o.addProperty("min", 0);
        o.addProperty("max", 1);
        final List<SpellAbilityView> got = pick(abilities, ask("ability", o, new JsonArray()));
        return got.isEmpty() ? null : got.get(0);
    }

    /** The damage that kills it, as Forge's own window counts it (VAssignCombatDamage.getDamageToKill). */
    private int lethal(final CardView attacker, final GameEntityView target) {
        if (target instanceof PlayerView p) {
            return attacker.getCurrentState().hasInfect()
                    ? Math.max(0, getGameView().getPoisonCountersToLose() - p.getCounters(forge.game.card.CounterEnumType.POISON))
                    : p.getLife();
        }
        final CardView c = (CardView) target;
        if (c.getCurrentState().isPlaneswalker() || c.getCurrentState().isBattle()) {
            try {
                return Integer.parseInt(c.getCurrentState().isPlaneswalker() ? c.getCurrentState().getLoyalty()
                        : c.getCurrentState().getDefense());
            } catch (final RuntimeException e) {
                return 0;
            }
        }
        final int n = Math.max(0, c.getLethalDamage());
        return attacker.getCurrentState().hasDeathtouch() ? Math.min(n, 1) : n;
    }

    /**
     * Combat damage shared out between the creatures blocking (and what was attacked, when the damage
     * may go on to it). Kind `damage`: the answer is one number for each option, adding up to `amount`;
     * "skip" puts it off when `may_skip`.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
            final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final List<CardView> list = blockers == null ? new ArrayList<>() : blockers;
        final boolean divide = attacker.getCurrentState().hasDivideDamage();
        final boolean toDefender = defender != null && (attacker.getCurrentState().hasTrample() || divide && overrideOrder
                || list.isEmpty());
        final List<GameEntityView> targets = new ArrayList<>(list);
        if (toDefender) {
            targets.add(defender);
        }
        final JsonObject o = question("Combat damage");
        o.add("card", JsonView.id(attacker));
        o.addProperty("amount", damage);
        o.add("defender", JsonView.entity(defender));
        o.addProperty("may_skip", maySkip);
        o.addProperty("any_order", overrideOrder);
        o.addProperty("deathtouch", attacker.getCurrentState().hasDeathtouch());
        final JsonArray options = new JsonArray();
        final JsonArray standard = new JsonArray();
        final int[] kills = new int[targets.size()];
        int left = damage;
        for (int i = 0; i < targets.size(); i++) {
            final GameEntityView t = targets.get(i);
            kills[i] = lethal(attacker, t);
            final JsonObject x = JsonView.entity(t);
            x.addProperty("i", i);
            x.addProperty("text", String.valueOf(t));
            x.addProperty("lethal", kills[i]);
            x.addProperty("defender", t == defender);
            options.add(x);
            // the usual way: what kills each in turn, whatever is left to the last
            final int n = i == targets.size() - 1 ? left : Math.min(kills[i], left);
            standard.add(n);
            left -= n;
        }
        o.add("options", options);
        if (targets.isEmpty()) {
            return null;
        }
        final JsonElement v = ask("damage", o, standard);
        if (maySkip && v.isJsonPrimitive() && "skip".equals(v.getAsString())) {
            return null;        // decide later: Forge asks again after the other attackers
        }
        JsonArray given = v.isJsonArray() ? v.getAsJsonArray() : standard;
        if (!validDamage(given, kills, damage, toDefender, overrideOrder || divide)) {
            error("That isn't a way to share out " + damage + " damage: the usual way is used.");
            given = standard;
        }
        final Map<CardView, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            final int n = given.get(i).getAsInt();
            if (n > 0) {
                map.put(targets.get(i) == defender ? null : (CardView) targets.get(i), n);      // null: what was attacked
            }
        }
        return map;
    }

    /** All of it shared out, nothing below zero – and, in the usual order, nothing past a creature that isn't dead yet. */
    private static boolean validDamage(final JsonArray given, final int[] kills, final int damage, final boolean toDefender,
            final boolean anyOrder) {
        if (given.size() != kills.length) {
            return false;
        }
        try {
            int sum = 0;
            boolean allDead = true;
            for (int i = 0; i < kills.length; i++) {
                final int n = given.get(i).getAsInt();
                if (n < 0) {
                    return false;
                }
                final boolean isDefender = toDefender && i == kills.length - 1;
                if (n > 0 && !allDead && (!anyOrder || isDefender)) {
                    return false;
                }
                if (!isDefender) {
                    allDead &= n >= kills[i];
                }
                sum += n;
            }
            return sum == damage;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * An amount shared out between several things (damage divided as you choose, counters, a shield,
     * mana of several colours). Kind `amount`: the answer is one number for each option, adding up
     * to `amount`.
     */
    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target,
            final int amount, final boolean atLeastOne, final String amountLabel) {
        final List<Object> keys = new ArrayList<>(target.keySet());
        final JsonObject o = question(amountLabel);
        o.add("card", JsonView.id(effectSource));
        o.addProperty("amount", amount);
        o.addProperty("at_least_one", atLeastOne);
        final JsonArray options = options(keys, null);
        final JsonArray standard = new JsonArray();
        int left = amount;
        for (int i = 0; i < keys.size(); i++) {
            final Integer most = target.get(keys.get(i));
            options.get(i).getAsJsonObject().addProperty("max", most == null ? amount : most);
            final int floor = atLeastOne ? 1 : 0;
            final int after = atLeastOne ? keys.size() - 1 - i : 0;       // what the ones after this must still get
            final int n = Math.max(floor, Math.min(most == null ? left : most, left - after));
            standard.add(Math.max(0, Math.min(n, left)));
            left -= standard.get(i).getAsInt();
        }
        o.add("options", options);
        final Map<Object, Integer> map = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return map;
        }
        final JsonElement v = ask("amount", o, standard);
        JsonArray given = v.isJsonArray() ? v.getAsJsonArray() : standard;
        boolean valid = given.size() == keys.size();
        int sum = 0;
        try {
            for (int i = 0; valid && i < keys.size(); i++) {
                final int n = given.get(i).getAsInt();
                final Integer most = target.get(keys.get(i));
                valid = n >= (atLeastOne ? 1 : 0) && (most == null || n <= most);
                sum += n;
            }
        } catch (final RuntimeException e) {
            valid = false;
        }
        if (!valid || sum != amount) {
            if (given != standard) {
                error("That isn't a way to share out " + amount + ": the usual way is used.");
            }
            given = standard;
        }
        for (int i = 0; i < keys.size(); i++) {
            map.put(keys.get(i), given.get(i).getAsInt());
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

    /**
     * Watching fast: don't stop at each phase (Forge's playback waits a moment at those it stops at).
     * A person playing: asked what to do only at their stops (while the stack is empty).
     */
    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        if (!interactive) {
            return quiet || wantedSpeed == PlaybackSpeed.FAST;
        }
        if (dev.isSkipping()) {
            return true;        // `skip_to`: the person's priority is passed for them
        }
        if (dev.holdsPriority()) {
            return false;       // asked at every step
        }
        final PlayerView me = getCurrentPlayer();
        return !stops(me != null && playerTurn != null && playerTurn.getId() == me.getId()).contains(phase);
    }
}
