# MSE Engine – this fork is the engine

This repository is the Forge fork MSE Studio's engine is built from (GPL-3.0, like Forge). It is not a
copy that follows Forge: **we decide what goes in**. The upstream project (Card-Forge/forge) still supplies
card data and fixes, which are taken on purpose – never by merging its history in.

## Branches

| Branch | What it is |
|---|---|
| `mse-engine` | **the main line**: the bridge, the engine's own work, the versions MSE Studio runs. Releases are built from this branch. |
| `master` | a mirror of upstream, **read-only**. Kept only so `upstream/master` can be compared and cherry-picked from. |
| `mse-bridge*`, `mse-bridge-dev`, `mse-ai-seats` | the bridge's development, merged into `mse-engine` when it is done and tested. |
| `own/*` | one branch per piece of the own-engine work (own/P1, own/upstream-<date>, …). |

Nothing is deleted on GitHub without the owner's agreement.

## Taking an upstream fix (the routine)

1. `git fetch upstream` (remote `upstream` = `https://github.com/Card-Forge/forge.git`; add it once if it is
   missing: `git remote add upstream https://github.com/Card-Forge/forge.git`).
2. Make a branch `own/upstream-<date>` from `mse-engine`.
3. `git cherry-pick -x <commit>` – only the commits the owner chose from `tools/upstream_report.py`
   (or that a known bug needs).
4. Run the full checks: the engine repository's tests, `tests/corpus/` and `tools/difftest.py` against
   `ref-0`, plus a card-load if card data changed. Report what plays differently (a fix that changes a game
   is sometimes the point).
5. The owner approves, then the branch is merged into `mse-engine`; the release follows the usual path
   (`package/build_addon.py`'s `VERSION`, the workflow, `external.json` in the private add-ons).

**Card scripts and editions** are separate: new/changed files under `forge-gui/res/cardsfolder` and
`forge-gui/res/editions` are copied in as one commit of their own (no code), after the card-load gate
(`forge-check/LoadAll.java`) passes. How often is the owner's choice.

## Version

- `forge.mse.EngineVersion.ENGINE` is our version (`MSE Engine 1.0.0-dev`), sent in the bridge's `hello` as
  `engine`, with `engine_base` naming the upstream commit it started from (`09b7eda5`).
- The **add-on's** version (what MSE Studio downloads) is `VERSION` in `package/build_addon.py`; the
  workflow releases `mtg-engine-v<VERSION>`.
- The bridge protocol number (`PROTOCOL` in `plans/mse-play.md`) changes only when the JSON contract does;
  `engine`/`engine_base` are additive.

## Upstream's workflows

Upstream's `.github/workflows/` files are still in the mirror, but **the fork runs none of them**: on
16 November 2026 GitHub's API lists no workflows at all for `MEZTKING/forge` (Actions is enabled, nothing is
registered – a fork's inherited workflows are inactive), and a direct request to disable one answers 404.
Nothing is costing minutes. If GitHub ever registers one, turn it off in **Settings → Actions → Workflows**;
the candidates are `publish-android`, `remove-stale-branches`, `snapshots-*`, `stale`, `sync-wiki`,
`test-android-build`, `test-maven-settings`, `ios-*` and `maven-publish` (keep `mtg-fetch-cards` if it is
wanted for card data). The owner approved turning them off on 16 November 2026; there was nothing left to
turn off.

## Notice

This engine is a modified version of Forge (Card-Forge/forge), GPL-3.0. Forge's own notice and licence stay
in the repository (`LICENSE.txt`, `README.md`); modifications are our own and are GPL-3.0 too. MSE Studio's
engine add-on bundles it unmodified from the release workflow.
