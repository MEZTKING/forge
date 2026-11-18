package forge.game.mse;

import java.util.Map;

import forge.game.staticability.StaticAbility;

/**
 * One continuous static effect a card script can name as {@code S:Mode$ Continuous | Mse$ <name>}
 * (P4-B): the piece supplies standard Continuous parameters (AddPower, AddKeyword, …) and Forge's own layer
 * system applies and removes them, so cleanup is the layer's own.
 */
public interface MseStaticPiece {
    String name();

    /** The Continuous parameters this piece adds (Affected and the like stay in the card's script). */
    Map<String, String> params(StaticAbility ability);
}
