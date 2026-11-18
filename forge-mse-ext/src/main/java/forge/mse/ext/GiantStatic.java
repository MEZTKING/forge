package forge.mse.ext;

import java.util.Map;

import forge.game.mse.MseStaticPiece;
import forge.game.staticability.StaticAbility;

/**
 * The first test continuous static of the extension layer: {@code Mse$ giant} gives +1/+1 through Forge's own
 * layer system (the script still says whom it affects). It exists to prove the plumbing (P4-B), not as a rule
 * of any deck.
 */
public final class GiantStatic implements MseStaticPiece {
    @Override
    public String name() {
        return "giant";
    }

    @Override
    public Map<String, String> params(final StaticAbility ability) {
        return Map.of("AddPower", "1", "AddToughness", "1");
    }
}
