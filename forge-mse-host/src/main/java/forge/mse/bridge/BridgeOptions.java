package forge.mse.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MSE Play's testing options for one game, as BridgeMain was started with them (plans/mse-play.md,
 * "Testing"). They are told to the table in `hello` (`testing`, `options`). A list a seat
 * ("A,B,C,D") gives the first seat A, the second B…; the last value stands for the seats after it.
 */
public final class BridgeOptions {
    public boolean testing = false;
    public List<Integer> life = new ArrayList<>();          // empty: the format's
    public String first = "random";                         // me, opponent, random, or a seat number
    public String mulligan = "";                            // london, vancouver, paris, original, houston, none (= keep)
    public int handSize = -1;
    public List<Integer> poison = new ArrayList<>();
    public List<String> counters = new ArrayList<>();       // a seat: "ENERGY=3+EXPERIENCE=1"
    public int players = 0;                                 // 0: one seat a deck
    public String gameType = "Commander";
    public int commanderDamage = -1;
    public int poisonToLose = -1;
    public Boolean commanderTax = null;
    public Boolean drawLoss = null;
    public int turnLimit = 0;
    public boolean solo = false;
    public List<String> aiProfiles = new ArrayList<>();
    public List<String> aiModes = new ArrayList<>();
    public int aiTimeout = 30;
    public boolean showOpponentHand = false;
    public boolean aiLog = false;
    public String startingBoard = null;
    public String devOps = null;
    public boolean replayFast = false;
    public int replayStates = 0;                            // while catching up: every Nth state is still sent (0: none)
    public String replayUntil = null;                       // "TURN" or "TURN:PHASE" – a watched game catches up to here
    public boolean replayPause = false;
    public int checkpoints = -1;                            // -1: none; 0: every turn; N: and every N actions
    public final List<String> problems = new ArrayList<>(); // what couldn't be done, in plain words

    /** The names of the options that only say yes (no value follows them). */
    static final String[] FLAGS = { "testing", "solo", "show-opponent-hand", "ai-log", "replay-fast", "replay-pause",
        "no-commander-tax", "no-draw-loss" };

    static boolean isFlag(final String name) {
        for (final String f : FLAGS) {
            if (f.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Reads the options out of BridgeMain's arguments (name → value, without the dashes). */
    public static BridgeOptions parse(final Map<String, String> opt) {
        final BridgeOptions o = new BridgeOptions();
        o.testing = opt.containsKey("testing");
        o.life = numbers(opt.get("life"));
        o.first = opt.getOrDefault("first", "random").trim().toLowerCase();
        o.mulligan = opt.getOrDefault("mulligan", opt.getOrDefault("mulligans", "")).trim().toLowerCase();
        o.handSize = number(opt, "hand-size", -1);
        o.poison = numbers(opt.get("poison"));
        if (opt.containsKey("counters")) {
            for (final String part : opt.get("counters").split(",", -1)) {
                o.counters.add(part.trim());
            }
        }
        o.players = number(opt, "players", 0);
        o.gameType = opt.getOrDefault("game-type", opt.getOrDefault("format", "Commander"));
        o.commanderDamage = number(opt, "commander-damage", -1);
        o.poisonToLose = number(opt, "poison-to-lose", -1);
        if (opt.containsKey("no-commander-tax")) {
            o.commanderTax = false;
        } else if (opt.containsKey("commander-tax")) {
            o.commanderTax = yes(opt.get("commander-tax"));
        }
        if (opt.containsKey("no-draw-loss")) {
            o.drawLoss = false;
        } else if (opt.containsKey("draw-loss")) {
            o.drawLoss = yes(opt.get("draw-loss"));
        }
        o.turnLimit = number(opt, "turn-limit", 0);
        o.solo = opt.containsKey("solo");
        o.aiProfiles = words(opt.get("ai-profile"));
        o.aiModes = words(opt.get("ai-mode"));
        o.aiTimeout = number(opt, "ai-timeout", 30);
        o.showOpponentHand = opt.containsKey("show-opponent-hand");
        o.aiLog = opt.containsKey("ai-log");
        o.startingBoard = opt.get("starting-board");
        o.devOps = opt.get("dev-ops");
        o.replayFast = opt.containsKey("replay-fast");
        o.replayStates = number(opt, "replay-states", 0);
        o.replayUntil = opt.get("replay-until");
        o.replayPause = opt.containsKey("replay-pause");
        o.checkpoints = number(opt, "checkpoints", -1);
        return o;
    }

    /** True when something here changes the game from a plain one: such a game is a testing game. */
    public boolean changesTheGame() {
        return !life.isEmpty() && life.size() > 1 || !"random".equals(first) || !mulligan.isEmpty() || handSize >= 0
                || !poison.isEmpty() || !counters.isEmpty() || commanderDamage >= 0 || poisonToLose >= 0
                || commanderTax != null || drawLoss != null || turnLimit > 0 || solo || !aiModes.isEmpty()
                || showOpponentHand || startingBoard != null || devOps != null;
    }

    static boolean yes(final String text) {
        final String t = text == null ? "" : text.trim().toLowerCase();
        return !(t.equals("off") || t.equals("no") || t.equals("false") || t.equals("0"));
    }

    private static int number(final Map<String, String> opt, final String key, final int otherwise) {
        return opt.containsKey(key) ? Integer.parseInt(opt.get(key).trim()) : otherwise;
    }

    private static List<Integer> numbers(final String text) {
        final List<Integer> list = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (final String part : text.split(",")) {
                list.add(Integer.parseInt(part.trim()));
            }
        }
        return list;
    }

    private static List<String> words(final String text) {
        final List<String> list = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (final String part : text.split(",")) {
                list.add(part.trim());
            }
        }
        return list;
    }

    /** The value for a seat: its own, or the last one given; `otherwise` when none was. */
    static <T> T forSeat(final List<T> list, final int seat, final T otherwise) {
        if (list.isEmpty()) {
            return otherwise;
        }
        return list.get(Math.min(seat, list.size() - 1));
    }

    /** `options` in hello: everything that was asked for (what wasn't is left out). */
    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        if (!life.isEmpty()) {
            o.add("life", array(life));
        }
        o.addProperty("first", first);
        if (!mulligan.isEmpty()) {
            o.addProperty("mulligan", mulligan);
        }
        if (handSize >= 0) {
            o.addProperty("hand_size", handSize);
        }
        if (!poison.isEmpty()) {
            o.add("poison", array(poison));
        }
        if (!counters.isEmpty()) {
            o.add("counters", array(counters));
        }
        o.addProperty("game_type", gameType);
        if (commanderDamage >= 0) {
            o.addProperty("commander_damage", commanderDamage);
        }
        if (poisonToLose >= 0) {
            o.addProperty("poison_to_lose", poisonToLose);
        }
        if (commanderTax != null) {
            o.addProperty("commander_tax", commanderTax);
        }
        if (drawLoss != null) {
            o.addProperty("draw_loss", drawLoss);
        }
        if (turnLimit > 0) {
            o.addProperty("turn_limit", turnLimit);
        }
        o.addProperty("solo", solo);
        if (!aiProfiles.isEmpty()) {
            o.add("ai_profiles", array(aiProfiles));
        }
        if (!aiModes.isEmpty()) {
            o.add("ai_modes", array(aiModes));
        }
        o.addProperty("ai_timeout", aiTimeout);
        o.addProperty("show_opponent_hand", showOpponentHand);
        o.addProperty("ai_log", aiLog);
        if (startingBoard != null) {
            o.addProperty("starting_board", startingBoard);
        }
        if (devOps != null) {
            o.addProperty("dev_ops", devOps);
        }
        o.addProperty("replay_fast", replayFast);
        if (replayUntil != null) {
            o.addProperty("replay_until", replayUntil);
        }
        if (checkpoints >= 0) {
            o.addProperty("checkpoints", checkpoints);
        }
        if (!problems.isEmpty()) {
            o.add("problems", array(problems));
        }
        return o;
    }

    private static JsonArray array(final List<?> list) {
        final JsonArray a = new JsonArray();
        for (final Object x : list) {
            if (x instanceof Number n) {
                a.add(n);
            } else {
                a.add(String.valueOf(x));
            }
        }
        return a;
    }
}
