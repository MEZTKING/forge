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
 * --human: the first seat is a person, playing through the table (which must be there: no --no-wait).
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
    private static volatile BridgeWire wire;
    private static volatile JsonGuiGame gui;

    private BridgeMain() {
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> opt = new HashMap<>();
        final List<String> ai = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            final String a = args[i];
            if (a.equals("--no-wait") || a.equals("--human") || a.equals("--auto-pay")) {
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
        if (!opt.containsKey("me") || ai.isEmpty()) {
            usage("--me and --ai are needed");
        }
        if (!opt.containsKey("port") && !opt.containsKey("out")) {
            usage("--port or --out is needed");
        }
        final long seed = opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : new Random().nextLong();
        final PlaybackSpeed speed = PlaybackSpeed.valueOf(opt.getOrDefault("speed", "normal").toUpperCase());
        final boolean noWait = opt.containsKey("no-wait") || !opt.containsKey("port");
        final boolean humanPlays = opt.containsKey("human");
        if (humanPlays && noWait) {
            usage("--human needs a table: --port, and no --no-wait");
        }
        final String name = opt.getOrDefault("name", "You");

        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");      // as Forge's own start does
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ":");
            ex.printStackTrace();
            final JsonGuiGame g = gui;
            if (g != null) {
                g.error("Forge stopped in " + t.getName() + ": " + ex);
            }
        });

        // the socket first: a port that's taken should say so before Forge takes its half minute to load
        wire = new BridgeWire(opt.containsKey("port") ? Integer.parseInt(opt.get("port")) : -1,
                opt.containsKey("out") ? Paths.get(opt.get("out")) : null);
        if (wire.getPort() >= 0) {
            System.out.println("MSE-BRIDGE port " + wire.getPort());
            System.out.flush();
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
            if (humanPlays) {
                p.setPref(FPref.PLAYER_NAME, name);                     // else Forge asks for one in a dialog
                p.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);   // the prompt's `playable` cards
                p.setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, true);         // and what Auto would tap
                if (speed == PlaybackSpeed.FAST) {
                    p.setPref(FPref.YIELD_SKIP_PHASE_DELAY, true);
                    p.setPref(FPref.YIELD_SKIP_RESOLVE_DELAY, true);
                }
            }
            return null;
        });

        // only now: GameType needs Forge's words loaded
        final GameType type = GameType.valueOf(opt.getOrDefault("format", "Commander"));
        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setGamesPerMatch(1);
        final List<RegisteredPlayer> players = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        files.add(opt.get("me"));
        files.addAll(ai);
        for (int i = 0; i < files.size(); i++) {
            final File f = new File(files.get(i));
            final Deck d = f.isFile() ? DeckSerializer.fromFile(f) : null;
            if (d == null) {
                usage("Can't read the deck " + f.getAbsolutePath());
            }
            final RegisteredPlayer rp = type == GameType.Commander || type == GameType.Brawl || type == GameType.Oathbreaker
                    || type == GameType.TinyLeaders ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
            if (opt.containsKey("life")) {
                rp.setStartingLife(Integer.parseInt(opt.get("life")));
            }
            if (humanPlays && i == 0) {
                // Forge's own "the person at this computer": HostedMatch gives the seat a
                // PlayerControllerHuman whose window is the JsonGuiGame
                final forge.LobbyPlayer person = GamePlayerUtil.getGuiPlayer();
                person.setName(name);
                rp.setPlayer(person);
            } else {
                rp.setPlayer(GamePlayerUtil.createAiPlayer(d.getName(), i));
            }
            players.add(rp);
        }

        gui = new JsonGuiGame(wire, seed, speed);
        base.setGui(gui);
        final CountDownLatch over = new CountDownLatch(1);
        if (humanPlays) {
            gui.setInteractive(true);
            gui.setAutoPay(opt.containsKey("auto-pay"));
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
                    gui.setStops(mine, theirs);
                } catch (final IllegalArgumentException e) {
                    usage("--stops: " + e.getMessage() + " isn't a phase (UPKEEP, DRAW, MAIN1, COMBAT_BEGIN, "
                            + "COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE, "
                            + "COMBAT_END, MAIN2, END_OF_TURN, CLEANUP)");
                }
            }
        }
        wire.setOnDisconnect(() -> {
            System.out.println("MSE-BRIDGE the table left");
            finish(gui.isGameOverSent() ? 0 : 3);
        });
        wire.start();
        if (!noWait) {
            System.out.println("MSE-BRIDGE ready");
            System.out.flush();
            if (!wire.awaitClient(Long.parseLong(opt.getOrDefault("wait", "120")))) {
                System.out.println("MSE-BRIDGE no table connected");
                finish(3);
            }
        }

        MyRandom.setRandom(new Random(seed));
        final HostedMatch match = new HostedMatch();
        match.setOnMatchOver(over::countDown);
        if (humanPlays) {
            match.startMatch(rules, null, players, players.get(0), gui);
        } else {
            // no person at the table: HostedMatch asks BridgeGui for a window and registers it as a spectator
            match.startMatch(rules, null, players, (RegisteredPlayer) null, null);
        }

        final long timeout = Long.parseLong(opt.getOrDefault("timeout", "0"));
        if (timeout > 0) {
            if (!over.await(timeout, TimeUnit.SECONDS)) {
                final JsonObject o = new JsonObject();
                o.addProperty("t", "game_over");
                o.add("winner", JsonNull.INSTANCE);
                o.addProperty("draw", true);
                o.addProperty("reason", "Timeout");
                wire.send(o);
                System.out.println("MSE-BRIDGE the game took more than " + timeout + " seconds");
                finish(3);
            }
        } else {
            over.await();
        }
        System.out.println("MSE-BRIDGE game over");
        finish(0);
    }

    /** Sends what's still queued, closes the line and ends the program. */
    public static void finish(final int code) {
        final JsonGuiGame g = gui;
        if (g != null) {
            g.releaseQuestions();
        }
        final BridgeWire w = wire;
        if (w != null) {
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
                + " [--human] [--name NAME] [--auto-pay] [--stops \"mine=MAIN1,MAIN2;theirs=END_OF_TURN\"]");
        System.exit(2);
    }
}
