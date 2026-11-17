package forge.mse.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.control.PlaybackSpeed;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * MSE Play's bridge: plays one game in Forge and tells it, as JSON lines, to a table connected
 * on 127.0.0.1 (the messages: plans/mse-play.md in the engine repository). Without --human every
 * player is Forge's AI and the table only watches; with it the table plays the --me deck.
 *
 * <pre>
 * java -cp forge.jar forge.mse.bridge.BridgeMain --port N --profile run/forge.profile.properties
 *      --me "My deck.dck" --ai "Their deck.dck" [--seed S] [--out game.jsonl] [--speed fast|normal|slow]
 *      [--format Commander] [--life N] [--no-wait] [--timeout SECONDS]
 *      [--human] [--name NAME] [--auto-pay] [--stops "mine=MAIN1,MAIN2;theirs=COMBAT_DECLARE_ATTACKERS"]
 * </pre>
 *
 * Testing (plans/mse-play.md, "Testing"; {@link BridgeOptions}, {@link DevTools}): --testing lets the table
 * send `dev` messages; --life A,B --first me|opponent|random --mulligan … --hand-size N --poison A,B
 * --counters … --players N (more --ai decks) --game-type … --commander-damage N --poison-to-lose N
 * --no-commander-tax --no-draw-loss --turn-limit N --solo --ai-profile … --ai-mode … --ai-timeout S
 * --show-opponent-hand --ai-log --starting-board FILE --dev-ops FILE; for continuing a kept game
 * --replay-fast --replay-states N --replay-until TURN[:PHASE] --replay-pause --checkpoints N.
 *
 * --human: the first seat is a person, playing through the table (which must be there: no --no-wait).
 * --seat2 client: the second seat is a person too, playing through a second line of its own (the port
 * is printed as "MSE-BRIDGE port N seat 1"; --port2 says which, else the port after --port). Each
 * line carries that seat's own view: the hidden-card rule holds per seat. --name/--name2 name them.
 * --auto-pay: costs Forge can pay by itself are paid without asking. --stops: the phases at which the
 * person is asked what to do while the stack is empty (Forge's names; "all", "none"; a side left out
 * keeps Forge's own settings). With --speed fast Forge's short waits between phases are left out.
 *
 * Start it in the folder that has Forge's res/ (a checkout's forge-gui, or the add-on's folder),
 * as every Forge run. --profile is copied there as forge.profile.properties (the converter's
 * run: its own user folder and cache). --port 0 takes any free port; the one taken is printed
 * as "MSE-BRIDGE port N". Without --no-wait the game starts when a table has connected.
 *
 * Exit codes: 0 the game ended, 2 bad arguments or decks, 3 no table came / it left / too slow.
 */
public final class BridgeMain {
    private static volatile BridgeWire wire;     // the first seat's line (kept so finish() can find it)
    private static volatile JsonGuiGame gui;     // the first seat's view: the testing tools and the transcript
    private static final List<BridgeWire> wires = new ArrayList<>();
    private static final List<JsonGuiGame> guis = new ArrayList<>();

    private BridgeMain() {
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> opt = new HashMap<>();
        final List<String> ai = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            final String a = args[i];
            if (a.equals("--no-wait") || a.equals("--human") || a.equals("--auto-pay")
                    || a.startsWith("--") && BridgeOptions.isFlag(a.substring(2))) {
                opt.put(a.substring(2), "1");
            } else if (a.startsWith("--") && i + 1 < args.length) {
                if (a.equals("--ai")) {
                    ai.add(args[++i]);
                } else {
                    opt.put(a.substring(2), args[++i]);
                }
            } else {
                usage("Unknown argument: " + a);
            }
        }
        if (!opt.containsKey("me") || ai.isEmpty() && !opt.containsKey("solo")) {
            usage("--me and --ai are needed (or --solo: nobody on the other side)");
        }
        BridgeOptions options = null;
        try {
            options = BridgeOptions.parse(opt);
        } catch (final RuntimeException e) {
            usage("An option isn't what it should be: " + e.getMessage());
        }
        final BridgeOptions o = options;
        o.testing = o.testing || o.changesTheGame();
        if (!opt.containsKey("port") && !opt.containsKey("out")) {
            usage("--port or --out is needed");
        }
        final long seed = opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : new Random().nextLong();
        forge.util.RandomLog.start(opt.get("decisions-out"), opt.get("decisions-in"));    // P3-B: random draws
        final PlaybackSpeed speed = PlaybackSpeed.valueOf(opt.getOrDefault("speed", "normal").toUpperCase());
        final boolean noWait = opt.containsKey("no-wait") || !opt.containsKey("port");
        final boolean humanPlays = opt.containsKey("human");
        final boolean seat2Client = "client".equalsIgnoreCase(opt.getOrDefault("seat2", ""));
        if ((humanPlays || seat2Client) && noWait) {
            usage("--human or --seat2 client needs a table: --port, and no --no-wait");
        }
        final String name = opt.getOrDefault("name", "You");

        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");      // as Forge's own start does
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ":");
            ex.printStackTrace();
            for (final JsonGuiGame g : guis) {
                g.error("Forge stopped in " + t.getName() + ": " + ex);
            }
        });

        // the sockets first: a port that's taken should say so before Forge takes its half minute to load
        final Path out = opt.containsKey("out") ? Paths.get(opt.get("out")) : null;
        final int port0 = opt.containsKey("port") ? Integer.parseInt(opt.get("port")) : -1;
        wire = new BridgeWire(port0, out);
        wires.add(wire);
        if (wire.getPort() >= 0) {
            System.out.println("MSE-BRIDGE port " + wire.getPort());
            System.out.flush();
        }
        if (seat2Client) {       // the second seat plays through its own line, like the first
            final int port1 = opt.containsKey("port2") ? Integer.parseInt(opt.get("port2"))
                    : port0 > 0 ? port0 + 1 : port0;
            final BridgeWire secondWire = new BridgeWire(port1,
                    out == null ? null : Paths.get(out + ".seat1.jsonl"));
            wires.add(secondWire);
            if (secondWire.getPort() >= 0) {
                System.out.println("MSE-BRIDGE port " + secondWire.getPort() + " seat 1");
                System.out.flush();
            }
        }

        final BridgeGui base = new BridgeGui();
        GuiBase.setInterface(base);
        // Before anything reads ForgeConstants: that class reads the profile once, when it's first used,
        // and without one Forge would use the player's real Forge folders.
        final File res = new File(base.getAssetsDir() + "res");
        if (!res.isDirectory()) {
            usage("Forge's res folder isn't here (" + res.getAbsolutePath() + "): start in the folder that has it");
        }
        if (opt.containsKey("profile")) {
            final Path from = Paths.get(opt.get("profile")).toAbsolutePath();
            final Path to = Paths.get(base.getAssetsDir() + "forge.profile.properties").toAbsolutePath();
            if (!Files.isRegularFile(from)) {
                usage("The profile isn't there: " + from);
            }
            if (!Files.exists(to) || !Files.isSameFile(from, to)) {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        FModel.initialize(null, p -> {
            p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            p.setPref(FPref.UI_ENABLE_SOUNDS, false);
            p.setPref(FPref.UI_ENABLE_MUSIC, false);
            p.setPref(FPref.UI_RECORD_REPLAYS, false);      // Forge's own replay files: not wanted here
            // Forge's AI gives up thinking after this many seconds (5 as it comes): a game that is to be
            // played again the same way mustn't depend on how fast the computer was
            p.setPref(FPref.MATCH_AI_TIMEOUT, String.valueOf(o.aiTimeout));
            if (humanPlays) {
                p.setPref(FPref.PLAYER_NAME, name);                     // else Forge asks for one in a dialog
                p.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);   // the prompt's `playable` cards
                p.setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, true);         // and what Auto would tap
                if (speed == PlaybackSpeed.FAST || o.replayFast) {
                    p.setPref(FPref.YIELD_SKIP_PHASE_DELAY, true);
                    p.setPref(FPref.YIELD_SKIP_RESOLVE_DELAY, true);
                }
            }
            return null;
        });

        // only now: GameType needs Forge's words loaded
        GameType type0 = null;
        try {
            type0 = GameType.valueOf(o.gameType);
        } catch (final IllegalArgumentException e) {
            usage("--game-type: Commander, Brawl, Oathbreaker, TinyLeaders or Constructed – not " + o.gameType);
        }
        final GameType type = type0;
        final boolean commander = type == GameType.Commander || type == GameType.Brawl || type == GameType.Oathbreaker
                || type == GameType.TinyLeaders;
        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setGamesPerMatch(1);
        if (o.poisonToLose >= 0) {
            rules.setPoisonCountersToLose(o.poisonToLose);
        }
        if (o.commanderDamage >= 0) {
            rules.setCommanderDamageToLose(o.commanderDamage);
        }
        if (o.commanderTax != null) {
            rules.setCommanderTax(o.commanderTax);
        }
        if (o.drawLoss != null) {
            rules.setDrawFromEmptyLibraryLoses(o.drawLoss);
        }
        switch (o.mulligan) {
            case "":
                break;
            case "none":
            case "keep":
                rules.setMulligansAllowed(false);
                break;
            case "london":
            case "vancouver":
            case "paris":
            case "original":
            case "houston":
                forge.StaticData.instance().setMulliganRule(forge.MulliganDefs.GetRuleByName(
                        o.mulligan.substring(0, 1).toUpperCase() + o.mulligan.substring(1)));
                break;
            default:
                usage("--mulligan: london, vancouver, paris, original, houston, none or keep – not " + o.mulligan);
        }
        final List<String> profiles = forge.ai.AiProfileUtil.getAvailableProfiles();
        for (final String profile : o.aiProfiles) {
            if (!profiles.contains(profile)) {
                usage("--ai-profile: " + profiles + " – not " + profile);
            }
        }
        String usual = FModel.getPreferences().getPref(FPref.UI_CURRENT_AI_PROFILE);
        if (!profiles.contains(usual)) {
            usual = "Default";
        }
        final List<Deck> decks = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        files.add(opt.get("me"));
        files.addAll(ai);
        for (final String file : files) {
            final File f = new File(file);
            final Deck d = f.isFile() ? DeckSerializer.fromFile(f) : null;
            if (d == null) {
                usage("Can't read the deck " + f.getAbsolutePath());
            }
            decks.add(d);
        }
        if (decks.size() == 1) {        // --solo and no deck for the other side: one that does nothing
            final Deck nobody = new Deck("Nobody");
            nobody.getMain().add("Wastes", 100);
            decks.add(nobody);
        }
        final int seats = o.players > 0 ? o.players : decks.size();
        if (seats < 2 || seats > 4 && o.players > 0) {
            usage("--players: 2, 3 or 4");
        }
        final List<RegisteredPlayer> players = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int aiSeat = 0;
        for (int i = 0; i < seats; i++) {
            // more seats than decks: the opponents' decks again, in turn
            final Deck d = i < decks.size() ? decks.get(i) : decks.get(1 + (i - 1) % (decks.size() - 1));
            final RegisteredPlayer rp = commander ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
            if (!o.life.isEmpty()) {
                rp.setStartingLife(BridgeOptions.forSeat(o.life, i, rp.getStartingLife()));
            }
            if (o.handSize >= 0) {
                rp.setStartingHand(o.handSize);
            }
            if (humanPlays && i == 0) {
                // Forge's own "the person at this computer": HostedMatch gives the seat a
                // PlayerControllerHuman whose window is the JsonGuiGame
                final forge.LobbyPlayer person = GamePlayerUtil.getGuiPlayer();
                person.setName(name);
                rp.setPlayer(person);
                names.add(name);
            } else if (seat2Client && i == 1) {
                // the second seat is a person too, but its window is the second line's JsonGuiGame
                final String name2 = opt.getOrDefault("name2", d.getName());
                final forge.LobbyPlayer person = new forge.player.LobbyPlayerHuman(name2);
                person.setAvatarIndex(i);
                person.setSleeveIndex(0);
                rp.setPlayer(person);
                names.add(name2);
            } else {
                String aiName = d.getName();
                for (int n = 2; names.contains(aiName); n++) {
                    aiName = d.getName() + " (" + n + ")";
                }
                names.add(aiName);
                BridgeAi.Mode mode = o.solo && i > 0 ? BridgeAi.Mode.PASSIVE : BridgeAi.Mode.NORMAL;
                try {       // --ai-mode and --ai-profile count the AI seats: the first is the first opponent
                    final int nth = aiSeat++;
                    if (!o.aiModes.isEmpty()) {
                        mode = BridgeAi.Mode.parse(BridgeOptions.forSeat(o.aiModes, nth, "normal"));
                    }
                    final BridgeAi seat = new BridgeAi(aiName, BridgeOptions.forSeat(o.aiProfiles, nth, usual), mode);
                    seat.setAvatarIndex(i);
                    seat.setSleeveIndex(0);
                    rp.setPlayer(seat);
                } catch (final IllegalArgumentException e) {
                    usage("--ai-mode: normal, passive, no-attack, no-block or no-cast");
                }
            }
            players.add(rp);
        }
        switch (o.first) {
            case "random":
                break;
            case "me":
                rules.setFirstPlayer(0);
                break;
            case "opponent":
                rules.setFirstPlayer(1);
                break;
            default:
                try {
                    rules.setFirstPlayer(Integer.parseInt(o.first));
                } catch (final NumberFormatException e) {
                    usage("--first: me, opponent, random or a seat (0, 1, 2, 3) – not " + o.first);
                }
        }

        // one view per client seat: Forge sets each human seat's window from this map (as its network
        // play does), so each seat gets its own stream, filtered for that seat alone
        final Map<RegisteredPlayer, IGuiGame> guisBySeat = new HashMap<>();
        final CountDownLatch over = new CountDownLatch(1);
        if (humanPlays) {
            gui = new JsonGuiGame(wires.get(0), seed, speed, o, 0, true);
            guis.add(gui);
            guisBySeat.put(players.get(0), gui);
        }
        if (seat2Client) {
            final JsonGuiGame second = new JsonGuiGame(wires.get(1), seed, speed, o, 1, gui == null);
            guis.add(second);
            guisBySeat.put(players.get(1), second);
            if (gui == null) {
                gui = second;       // only the second seat has a person: it owns the tools and the transcript
            }
        }
        if (gui == null) {
            // no person at the table: HostedMatch asks BridgeGui for a window and registers it as a spectator
            gui = new JsonGuiGame(wire, seed, speed, o, 0, true);
            guis.add(gui);
        }
        base.setGui(gui);
        if (!guisBySeat.isEmpty()) {
            for (int i = 0; i < guis.size(); i++) {
                final JsonGuiGame g = guis.get(i);
                g.setInteractive(true);
                // the second client seat is the AI service's (MSE Play's pilot): Forge pays its mana itself
                g.setAutoPay(i == 0 ? opt.containsKey("auto-pay") : true);
            }
            gui.setOnGameOver(over::countDown);     // nobody clicks Forge's own "quit" after the game
            if (opt.containsKey("stops")) {
                try {
                    java.util.Set<forge.game.phase.PhaseType> mine = null;
                    java.util.Set<forge.game.phase.PhaseType> theirs = null;
                    for (final String part : opt.get("stops").split(";")) {
                        final String[] kv = part.split("=", 2);
                        if (kv.length == 1) {       // one list: both sides
                            mine = JsonGuiGame.parsePhases(kv[0]);
                            theirs = mine;
                        } else if (kv[0].trim().equalsIgnoreCase("mine")) {
                            mine = JsonGuiGame.parsePhases(kv[1]);
                        } else if (kv[0].trim().equalsIgnoreCase("theirs")) {
                            theirs = JsonGuiGame.parsePhases(kv[1]);
                        } else {
                            usage("--stops: mine=… or theirs=…, not " + kv[0]);
                        }
                    }
                    for (final JsonGuiGame g : guis) {      // both client seats: fewer questions, less cost
                        g.setStops(mine, theirs);
                    }
                } catch (final IllegalArgumentException e) {
                    usage("--stops: " + e.getMessage() + " isn't a phase (UPKEEP, DRAW, MAIN1, COMBAT_BEGIN, "
                            + "COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE, "
                            + "COMBAT_END, MAIN2, END_OF_TURN, CLEANUP)");
                }
            }
        }
        for (final BridgeWire w : wires) {
            w.setOnDisconnect(() -> {
                System.out.println("MSE-BRIDGE the table left");
                finish(gui.isGameOverSent() ? 0 : 3);
            });
            w.start();
        }
        if (!noWait) {
            System.out.println("MSE-BRIDGE ready");
            System.out.flush();
            final long wait = Long.parseLong(opt.getOrDefault("wait", "120"));
            for (final BridgeWire w : wires) {
                if (!w.awaitClient(wait)) {
                    System.out.println("MSE-BRIDGE no table connected");
                    finish(3);
                }
            }
        }

        MyRandom.setRandom(new Random(seed));
        final HostedMatch match = new HostedMatch();
        match.setOnMatchOver(over::countDown);
        if (gui.hasStart()) {       // poison, counters, a starting board: set up just before the first turn
            match.setStartGameHook(gui::applyStart);
        }
        if (!guisBySeat.isEmpty()) {
            match.startMatch(rules, null, players, guisBySeat, null);
        } else {
            match.startMatch(rules, null, players, (RegisteredPlayer) null, null);
        }

        final long timeout = Long.parseLong(opt.getOrDefault("timeout", "0"));
        if (timeout > 0) {
            if (!over.await(timeout, TimeUnit.SECONDS)) {
                final JsonObject late = new JsonObject();
                late.addProperty("t", "game_over");
                late.add("winner", JsonNull.INSTANCE);
                late.addProperty("draw", true);
                late.addProperty("reason", "Timeout");
                for (final BridgeWire w : wires) {       // every seat hears the game is over
                    w.send(late);
                }
                System.out.println("MSE-BRIDGE the game took more than " + timeout + " seconds");
                finish(3);
            }
        } else {
            over.await();
        }
        System.out.println("MSE-BRIDGE game over");
        finish(0);
    }

    /** Sends what's still queued, closes the lines and ends the program. */
    public static void finish(final int code) {
        forge.util.RandomLog.close();
        for (final JsonGuiGame g : guis) {
            g.releaseQuestions();
        }
        for (final BridgeWire w : wires) {
            w.close();
        }
        System.out.flush();
        Runtime.getRuntime().halt(code);
    }

    private static void usage(final String problem) throws IOException {
        System.err.println(problem);
        System.err.println("java -cp forge.jar forge.mse.bridge.BridgeMain --port N --profile forge.profile.properties"
                + " --me deck.dck --ai deck.dck [--seed S] [--out game.jsonl] [--speed fast|normal|slow]"
                + " [--format Commander] [--life N] [--no-wait] [--wait SECONDS] [--timeout SECONDS]"
                + " [--human] [--seat2 client] [--name NAME] [--name2 NAME] [--port2 N]"
                + " [--auto-pay] [--stops \"mine=MAIN1,MAIN2;theirs=END_OF_TURN\"]"
                + " [--decisions-out FILE] [--decisions-in FILE]\n"
                + "testing: [--testing] [--life A,B,C,D] [--first me|opponent|random|SEAT]"
                + " [--mulligan london|vancouver|paris|original|houston|none|keep] [--hand-size N] [--poison A,B]"
                + " [--counters \"ENERGY=3+EXPERIENCE=1,POISON=2\"] [--players 2-4] [--ai more.dck]…"
                + " [--game-type Commander|Brawl|Oathbreaker|TinyLeaders|Constructed] [--commander-damage N]"
                + " [--poison-to-lose N] [--no-commander-tax] [--no-draw-loss] [--turn-limit N] [--solo]"
                + " [--ai-profile NAME,…] [--ai-mode normal|passive|no-attack|no-block|no-cast,…] [--ai-timeout SECONDS]"
                + " [--show-opponent-hand] [--ai-log] [--starting-board FILE] [--dev-ops FILE]"
                + " [--replay-fast] [--replay-states N] [--replay-until TURN[:PHASE]] [--replay-pause] [--checkpoints N]");
        System.exit(2);
    }
}
