package forge.game.replacement;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.mse.MseRegistry;
import forge.game.mse.MseReplacementPiece;

/**
 * The generic replacement entry of the extension layer (P4-B): {@code R:Event$ Mse | MseReplacement$ <name>}.
 * The piece (in <code>forge-mse-ext</code>) says which events it hears and whether it applies; the standard
 * {@code Execute$} ability does the rest.
 */
public class ReplaceMse extends ReplacementEffect {
    private final MseReplacementPiece piece;

    public ReplaceMse(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
        final String name = params.get("MseReplacement");
        this.piece = MseRegistry.replacement(name);
        if (this.piece == null) {
            throw new RuntimeException("No extension-layer replacement is registered as \"" + name
                    + "\" (the card script asks for it)");
        }
    }

    @Override
    public boolean modeCheck(final ReplacementType event, final Map<AbilityKey, Object> runParams) {
        return piece.modes().isEmpty() || piece.modes().contains(event);
    }

    @Override
    public boolean canReplace(final Map<AbilityKey, Object> runParams) {
        return piece.canReplace(this, runParams);
    }
}
