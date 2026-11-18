package forge.game.mse;

import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;

/**
 * One replacement a card script can name as {@code R:Event$ Mse | MseReplacement$ <name>} (P4-B). The
 * piece says which Forge replacement events it hears and whether it applies; the actual replacement runs the
 * usual {@code Execute$} ability.
 */
public interface MseReplacementPiece {
    String name();

    /** The Forge replacement events it hears (empty: every one). */
    Set<ReplacementType> modes();

    boolean canReplace(ReplacementEffect effect, Map<AbilityKey, Object> runParams);
}
