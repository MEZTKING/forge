package forge.mse.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Forge's AI at a seat of a bridge game, with MSE Play's testing switches: what it may do
 * ({@link Mode}), what it decided as `log` lines of kind AI ({@link #setLog}), and a pause before
 * it casts a spell ({@link #setPause}). With every switch off it plays exactly as Forge's AI does.
 */
public final class BridgeAi extends LobbyPlayerAi {
    /** What the AI may do: everything, nothing at all, or everything but one thing. */
    public enum Mode {
        NORMAL, PASSIVE, NO_ATTACK, NO_BLOCK, NO_CAST;

        public static Mode parse(final String text) {
            return valueOf(text.trim().toUpperCase().replace('-', '_'));
        }
    }

    private volatile Mode mode;
    private static volatile BiConsumer<Player, String> log;
    private static volatile Predicate<SpellAbility> pause;

    public BridgeAi(final String name, final String profile, final Mode mode0) {
        super(name, null);
        setAiProfile(profile);
        mode = mode0;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(final Mode mode0) {
        mode = mode0;
    }

    /** Where the AI's decisions are told (the player, a line in plain words), or null for nowhere. */
    public static void setLog(final BiConsumer<Player, String> log0) {
        log = log0;
    }

    /** Asked before the AI casts a spell or uses an ability (not for lands): returns when the game may go on. */
    public static void setPause(final Predicate<SpellAbility> pause0) {
        pause = pause0;
    }

    @Override
    public Player createIngamePlayer(final Game game, final int id) {
        final Player ai = new Player(getName(), game, id);
        ai.setFirstController(new Controller(game, ai));
        return ai;
    }

    private static void say(final Player p, final String text) {
        final BiConsumer<Player, String> l = log;
        if (l != null) {
            try {
                l.accept(p, text);
            } catch (final RuntimeException e) {
                // telling it must never stop the game
            }
        }
    }

    private final class Controller extends PlayerControllerAi {
        Controller(final Game game, final Player p) {
            super(game, p, BridgeAi.this);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (mode == Mode.PASSIVE) {
                return null;
            }
            final List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            if (chosen == null || chosen.isEmpty()) {
                return chosen;
            }
            if (mode == Mode.NO_CAST) {
                final List<SpellAbility> lands = new ArrayList<>();
                for (final SpellAbility sa : chosen) {
                    if (sa.isLandAbility()) {
                        lands.add(sa);
                    }
                }
                if (lands.isEmpty()) {
                    return null;
                }
                return lands;
            }
            for (final SpellAbility sa : chosen) {
                final Card host = sa.getHostCard();
                if (sa.isLandAbility()) {
                    say(player, player + " plays the land " + host + ".");
                    continue;
                }
                // a spell cast face down (morph, disguise…) is a face-down card to the other players too
                say(player, sa.isCastFaceDown() ? player + " chose to cast a card face down."
                        : player + " chose to " + (sa.isSpell() ? "cast " : "use ") + host + ": " + sa);
                final Predicate<SpellAbility> p = pause;
                if (p != null) {
                    p.test(sa);
                }
            }
            return chosen;
        }

        @Override
        public void declareAttackers(final Player attacker, final Combat combat) {
            if (mode == Mode.PASSIVE || mode == Mode.NO_ATTACK) {
                return;
            }
            super.declareAttackers(attacker, combat);
            if (log != null) {
                say(player, combat.getAttackers().isEmpty() ? player + " chose not to attack."
                        : player + " attacks with " + combat.getAttackers() + ".");
            }
        }

        @Override
        public void declareBlockers(final Player defender, final Combat combat) {
            if (mode == Mode.PASSIVE || mode == Mode.NO_BLOCK) {
                return;
            }
            super.declareBlockers(defender, combat);
            if (log != null) {
                say(player, combat.getAllBlockers().isEmpty() ? player + " chose not to block."
                        : player + " blocks with " + combat.getAllBlockers() + ".");
            }
        }

        @Override
        public boolean mulliganKeepHand(final Player firstPlayer, final int cardsToReturn) {
            final boolean keep = mode == Mode.PASSIVE || super.mulliganKeepHand(firstPlayer, cardsToReturn);
            say(player, player + (keep ? " keeps the hand." : " takes a mulligan."));
            return keep;
        }
    }
}
