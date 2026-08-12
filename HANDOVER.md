# Handover

For whoever picks this up next. `SPEC.md` is the full specification and
decision record — this document is only the things that aren't in it: where
the project actually stands, how to work on it productively, and the traps
that already cost real time once.

---

## 1. Where it stands

A working RuneLite plugin: 32 source files, 69 passing tests, 45 commits.
Every phase in `SPEC.md` §61 is built, and everything except parts of Phase 5
has been validated in live gameplay by the owner.

| Phase | State |
|---|---|
| 1 — session lifecycle | Done, validated |
| 2 — item flow, drops, retention | Done, validated |
| 3 — ground pickup, repickup | Done, validated |
| 4 — bank correlation, future XP | Done, validated |
| 5 — activity classification | Woodcutting + Fishing validated; Mining, Hunter, Farming, Cooking, Smithing, Runecraft built but not yet played |
| 6 — Slayer, KPH, loot flow, trips | Done, validated. **Task loadouts (§38) deliberately skipped** |
| 7 — UI design pass | Done, validated |

**Blocking release** (both need the owner, not code):
1. `icon.png` (≤48×72px listing thumbnail) and a real `NavigationButton`
   icon. The sidebar icon is still an "SI" circle drawn in code in
   `SkillingInfoPlugin.buildIcon()`.
2. §59's three screenshots.

Then submission is a PR to `runelite/plugin-hub` adding one file with the
repo URL and a commit hash.

**Known, deliberate omissions** — all recorded in `SPEC.md` with reasoning:
task loadouts (§38); `ITEM_TRANSFORMED`, which turned out unnecessary once
consumption existed (§18); trip *aggregation* (the boundaries are recorded,
nothing consumes them yet); Fletching/Herblore/Crafting product tables.

---

## 2. How to work on this

```bash
./gradlew build   # compiles and runs the tests
./gradlew run     # launches a real RuneLite client with the plugin loaded
```

JDK 11+ required. macOS needs the `--add-exports` JVM arg that's already in
`build.gradle`'s `run` task, or RuneLite won't start at all.

**The loop that worked, and it matters:** implement → `./gradlew build` →
the owner plays with it → fix from what they report. Roughly half the real
bugs in this project were invisible to the compiler and to the tests, and
surfaced only in play. Do not assume "it compiles and the tests pass" means
it works.

**When something doesn't work, get the log before theorising.** The debug
logging in `SessionManager` is deliberately left in — `Candidate tick`,
`Crediting generated`, `Crediting banked`, `Crediting pickup`,
`Inventory delta`. Every time we guessed instead of reading it, the guess
was wrong; every time we read it, the cause was obvious.

---

## 3. Traps that already cost time

These are the expensive lessons. Each one shipped, looked fine, and failed
silently.

**Verify every RuneLite API against source before using it.** Memory is
unreliable here and the codebase moves. Real examples from this project:
`FishingSpot` and `Axe` no longer exist; `Rock` is package-private so an
external plugin can't touch it; `net.runelite.api.InventoryID` is deprecated
in favour of `net.runelite.api.gameval.InventoryID`; several `ItemID`
constants aren't named what you'd expect (`CHINCHOMPA_CAPTURED`, not
`RED_CHINCHOMPA`; `BLANKRUNE`, not `PURE_ESSENCE`). Fetch the file and grep
it. It takes a minute and has caught something nearly every time.

**Never borrow another plugin's session-scoped numbers.** We delegated rates
to `XpTrackerService` and a session genuinely running at 20,348 XP/hr
displayed **15**, because its snapshot covers *its* session, which persists
across logins. The same applies to `SlayerPluginService.getInitialAmount()`.
If you need a figure scoped to our session, compute or baseline it yourself.
`SPEC.md` §14 has the full account.

**`ItemManager.getItemComposition()` asserts the client thread.**
`getImage()` does not — it returns an `AsyncBufferedImage`, and
`image.addTo(label)` is the correct pattern from the panel (it's what
`LootTrackerBox` does). Item *names* are resolved on the client thread in
`SkillingInfoPlugin.resolveItemNames()` and cached into a map the UI reads.

**Swing: never measure a component's preferred size at construction.** A
helper snapshotted `getPreferredSize()` and pinned it as the maximum, while
every label inside was still empty — so every multi-line row clipped to one
line, permanently. See `Ui.fixHeight`, which now only sets alignment.

**Swing: `refresh()` runs every game tick.** Rebuilding interactive
components there destroys them under the user's cursor — it made a dropdown
impossible to use. Cache rows and rebuild only when the *set* of items
changes (`CurrentView.refreshItemFlow`).

**A combat tick delivers several skills at once.** Attack, Strength,
Hitpoints and Slayer XP all arrive together and all collapse to one tracking
group (§7a). Any per-skill loop that increments something will over-count by
3–4×; that's exactly how one hit came to register as four "actions". Check
`TrackingGroups.isCombatGroup()` before counting per-drop.

**Beware rules that reject the whole batch on one bad element.** Session
detection required a minimum gap between *every* consecutive XP drop, so a
single fast pair discarded the entire buffer — and power-mining could never
start a session at all, with no error anywhere. It's now a check on the
buffer's total span.

**A UI exception can masquerade as a tracking failure.** One throw on the
EDT stops the panel updating entirely, which looks identical to "the plugin
stopped working". `SkillingInfoPlugin.refreshPanel()` guards against this
deliberately — keep it.

**Item flow ends in two catch-all rules that accept anything unexplained.**
Inside the XP window, an unclaimed inventory increase becomes generated
output and an unclaimed decrease becomes consumption. So every pool feeding
them must be drained *unconditionally* and claimed against in a fixed order
(§18 `[v8]`) — and it's the half you didn't write that bites: the wield
direction was handled and the unequip direction wasn't, so taking a glory
off mid-chop was recorded as a log; bank deposits were capped by a three-way
minimum and withdrawals weren't, so withdrawing 27 raw sharks was recorded
as catching them. Drain on one path only and the leftovers don't just leak,
they later cancel a real movement of the same item.

**Test this layer by driving ticks, not by calling the model.** Every test
before v8 poked `ActivitySession` directly, and the three bugs above all
needed two containers moving in the same tick to appear — so 62 green tests
said nothing about them. `SessionManagerItemFlowTest` shows the harness;
it's about 40 lines and needs no mocking framework.

---

## 4. Architecture in one paragraph

`session/` holds all logic and has no UI dependencies; `ui/` renders and
holds no logic. `SkillingInfoPlugin` is the only place that touches RuneLite
events, and it pushes into `SessionManager`, which owns the state machine
(§7) and the correlators. Each correlator follows the same shape: a bounded
pending ledger, matched against evidence within a short window, discarded on
timeout rather than carried forward — see `DropCorrelator`,
`PickupCorrelator`, `BankCorrelator`. Within a tick, more specific signals
claim their share of the inventory deltas first (drops before banking before
consumption), so one change can't be counted twice.

**The one rule that governs the data model:** raw item counts are
authoritative (§34) and derived figures sit *next to* them, never instead of
them. That's why a session's projected XP is frozen at finalisation
(`ProjectedXp`) — changing a product selection later must never silently
rewrite what a past session reported.

---

## 5. If you change the data model

`sessions.jsonl` lives at
`~/.runelite/skilling-info/<accountHash>/sessions.jsonl`, one completed
session per line, appended and never rewritten. Adding fields is safe: Gson
leaves absent fields null and `ActivitySession.getProjection()` already
tolerates it for pre-v2 records. Bump `schemaVersion` and say why in
`SPEC.md`. Removing or repurposing a field is not safe — real users' history
is in that file.

---

## 6. Working with the owner

They test every change in-game and report back precisely; take their reports
seriously, because several were symptoms of bugs deeper than described
("actions is confusing" was a 4× over-count). They are decisive on product
questions and prefer being given a recommendation with reasoning over a menu
of options. When you disagree with a decision, say so once with evidence —
that's how the `XpTrackerService` delegation got reverted — then implement
what they choose.

Keep updating `SPEC.md` as decisions are made. It is the reason this project
could be picked up cold, and several sections exist specifically because an
implementation attempt proved the original assumption wrong.
