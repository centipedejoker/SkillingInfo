# SKILLING INFO

RuneLite Personal Activity, Item-Flow & Account-Gain Analytics

**Document status:** v6 — Reviewed, hardened, prior-art-checked, scope-decided, deployment-ready & first-playtested implementation specification (2026-08-11)
**Target:** RuneLite Plugin Hub
**Primary goal:** Measure how the player actually performs OSRS activities and what their account actually gains from each session
**Secondary goal:** Produce structured local historical data for external analysis
**Implementation priority:** Simplicity → accuracy → compliance → reuse of existing RuneLite patterns
**Development model:** Autonomous coding agent with minimal owner involvement
**Language:** Java
**Initial target:** v0.1.0

> **Changelog from v1:** Sections 7, 9, 18, 20, 21, 22, 25, 26, 31, 32, 33, 39, 44, 52 revised to close correlation-algorithm gaps, a state-machine dead-end, and a formula error found during review. Changes are called out inline with `[v2]`.
>
> **Changelog from v2:** Section 5 corrected — the "Bossing Info" reference is real and currently listed on the Plugin Hub, under the internal name `kph-tracker` ([Mrnice98/BossingInfo](https://github.com/Mrnice98/BossingInfo)). A full read of its source confirmed it is architecturally the same framework this spec describes, aimed at bosses instead of skills: chat-message-driven activity detection in place of XP-drop detection, a session lifecycle with pause/resume/end, and an idle-inclusive vs. idle-exclusive rate split identical in spirit to §14's active/overall XP-per-hour. Sections 5, 7, 13, 44, 46, 48 updated with specific findings, marked `[v3]`.
>
> **Changelog from v3:** Six open decisions resolved. (1) §9's reward-burst filter had a logic bug that would have blocked all combat/Slayer detection — every hit awards Hitpoints XP alongside combat-style XP in the same tick, which the old rule read as a multi-skill reward burst. Fixed via a new **tracking-group** concept (§7a). (2) Combat scope is Slayer-task-only, not general PvM — codified in new §1a, enforced mechanically by §7a rather than left as a stated intention. (3) Genuine multi-skill session tracking (not a simplified single-skill shortcut) is built now, not deferred — §7a, plus matching changes in `SessionManager`/`ActivitySession`/`TrackingGroups`. (4) The idle-reset signal is broadened beyond XP to item-flow events (§13). (5) Future XP will use a small hardcoded item→XP table built alongside Phase 4, not a dependency on `banked-experience` or an indefinite deferral (§33). (6) Unclassified inventory movements are confirmed to stay invisible to the user, not surfaced even as a count (§50). Sections 1a (new), 7, 7a (new), 9, 13, 33, 37, 44, 50 updated, marked `[v4]`.
>
> **Changelog from v4:** New §65 (UI/UX Lock-Down) and §66 (Deployment Artifacts), verified directly against RuneLite's plugin submission docs rather than assumed. `LICENSE` (BSD 2-Clause) and `runelite-plugin.properties`'s missing `build`/`version` fields added to the scaffold; two still-open gaps (a real repo-root `icon.png` and finalized visual mockups) are called out explicitly rather than left implicit.
>
> **Changelog from v5:** First live playtest — the scaffold actually built and ran (Gradle wrapper generated, JDK/module fixes applied, `startUp()` confirmed clean with zero exceptions), surfacing the first real UX feedback rather than a hypothetical one. The plain Current/History text-tab switch is replaced with a skill-icon toggle row (§65), built on RuneLite core's own `SkillIconManager` and the small-font convention (`FontManager.getRunescapeSmallFont()`) taken directly from XP Tracker's source — both verified against runelite/runelite, not assumed. §61's Phase 1 checklist item for the sidebar is now implemented against real feedback instead of the original ASCII mockup guess.

---

## 1. PRODUCT DEFINITION

Skilling Info is a passive RuneLite analytics plugin for recording personal skilling, Slayer and repeatable activity sessions.

Its central question is:

> After playing this activity this way for this long, what did my account actually gain?

It records both:

**Performance**
- session duration
- active time
- idle time
- XP gained
- active XP/hour
- overall XP/hour
- actions completed where reliably measurable
- actions/hour
- activity-specific rates

**Account gain**
- items generated
- items acquired directly
- ground loot actually picked up
- items dropped
- items picked back up
- items consumed where reliably identifiable
- items confirmed banked
- net retained output
- future XP represented by retained/banked resources where appropriate

This item lifecycle is a core feature.

## 1a. COMBAT SCOPE `[v4]`

Combat sessions are in scope only when a Slayer task is being worked. Plain PvM/bossing without an active task (farming a boss purely for loot, no task running) is explicitly **out of scope**: `kph-tracker` (Bossing Info, §5) already covers boss KPH well, and duplicating it isn't the differentiator — Skilling Info's combat angle is the Slayer loot-flow story (§37), not kills-per-hour.

This isn't a UI-level restriction to remember to add later — it's enforced mechanically by §7a's detection rule, which only lets the combat tracking group reach its confidence gate once real Slayer XP is present. A player bossing without a task never generates Slayer XP, so detection never fires for them; no separate "is a task active" check is needed.

## 2. DIFFERENTIATOR

Traditional loot systems answer: "What dropped?"
Skilling Info answers: "What did I actually take, keep, drop, and bank?"

This distinction is especially valuable for Ironman progression and personal activity modelling.

Example — a Slayer monster drops:

```
Ranarr seed × 4
Pure essence × 4,000
Death rune × 700
Rune med helm × 7
Adamant arrows × 3,000
```

The player picks up:

```
Ranarr seed × 4
Death rune × 700
Rune med helm × 7
```

Then later drops:

```
Rune med helm × 2
```

Then banks:

```
Ranarr seed × 4
Death rune × 700
Rune med helm × 5
```

Skilling Info should be capable of representing:

```
GENERATED
ACQUIRED
DROPPED
PICKED UP AGAIN
BANKED
NET RETAINED
```

The account-gain dataset is more important than gross loot.

## 3. PRODUCT PRINCIPLE

Skilling Info follows: **OBSERVE → MEASURE → RECORD → SUMMARISE.**
Never: OBSERVE → DECIDE → ACT.

The plugin records gameplay. It does not perform gameplay.

## 4. STANDALONE RUNELITE PRODUCT

Skilling Info must be useful without any external application. RuneLite provides:
- session detection
- session approval prompt
- live sidebar statistics
- active/idle timing
- XP tracking
- activity outputs
- item-flow tracking
- session controls
- historical sessions
- local structured persistence

External analytical software is optional.

## 5. EXISTING PLUGIN REUSE

Before implementing any subsystem, inspect existing accepted RuneLite/core implementations.

**RuneLite XP Tracker** — reuse established approaches for `StatChanged`, XP deltas, XP/hour, actions, session state.

**Bossing Info** (Plugin Hub internal name `kph-tracker`; source: [Mrnice98/BossingInfo](https://github.com/Mrnice98/BossingInfo)) — reference for side-panel layout, session controls, idle handling, rate presentation, history, session lifecycle. `[v3]` A full source read confirms this plugin is the same framework this spec describes, built for bosses instead of skills. Specific things worth borrowing:
  - **Resume-on-next-event.** `chatMessageFilter()` auto-resumes a paused session the instant the next qualifying chat message arrives, rather than re-running the full detection gate. This is exactly §7 [v2]'s single-event resume, already shipped and working elsewhere.
  - **Idle-inclusive vs. idle-exclusive rate split** (`calcMode` 0 = "Actual," 1 = "Virtual"). Same concept as §14's active/overall XP-per-hour, independently validated.
  - **Reuse the core Loot Tracker's own event for drop capture.** `onLootReceived(LootReceived event)` (`net.runelite.client.plugins.loottracker.LootReceived`, filtered to `LootRecordType.NPC`/`EVENT`) is the concrete integration point for §18's `ITEM_GENERATED` stage in Phase 6 — don't re-derive NPC drops from raw events when the core plugin already exposes them.
  - **Scope local storage per account hash**, not one global file — see §44/§46 [v3].

  Specific things *not* to copy:
  - Its persistence is a hand-rolled, newline-delimited text file parsed by positional index and regex (`list.get(5).replaceAll("[^0-9]", "")`), fragile to any format drift between versions. §44's JSON Lines + Gson approach is a deliberate improvement — keep it.
  - Its ~1,600-line `KphPlugin` class mixes event subscription, timer arithmetic, chat-string parsing, and direct Swing mutation in one place. §48's separated `SessionManager`/`ActivityClassifier`/UI layers should stay separated as Phases 2–6 add complexity, not converge toward a single class for convenience.
  - Boss identification is a ~60-entry hardcoded enum with per-boss magic timeout values and alias maps, visibly the plugin's largest ongoing maintenance burden (duplicate-ID workarounds, "not included" placeholders for Barrows brothers). This validates §15/§16's "Unclassified is correct" default rather than building an exhaustive per-method classification table up front.
  - Its idle handling **ends** the session on timeout (`sessionTimeoutTimer()` → `sessionEnd()`) rather than pausing it. §7/§13's PAUSED state is an intentional divergence, not an oversight — see §13 [v3].

**Loot Tracker** — reference for item accumulation, item display, activity loot tracking.

**Ground Items** — reference for `ItemSpawned`, `ItemDespawned`, `ItemQuantityChanged`, ground item identity, tile/location.

**Inventory / equipment plugins** — reference for `ItemContainerChanged`, inventory snapshots, equipment snapshots, item presentation.

Use existing APIs and patterns. Do not unnecessarily recreate proven mechanisms.

## 6. CORE USER EXPERIENCE

The player begins repeatedly gaining XP. Skilling Info buffers candidate activity events. After several qualifying XP drops:

```
SKILLING INFO

Fishing activity detected

5 XP drops
38 seconds
+875 XP

Start skilling session?

[ START ]   [ IGNORE ]
```

Selecting START:
- creates a session
- starts retroactively at the first buffered qualifying event
- includes buffered XP
- includes buffered output events where safe
- begins live tracking

This avoids automatically recording lamps, quest XP, diary rewards, incidental XP, one-off skilling, miscellaneous reward XP.

## 7. SESSION STATE MACHINE `[v2]`

```
IDLE
  ↓ qualifying XP event
CANDIDATE
  ├── confidence gate met → PROMPTED
  ├── buffer window expires without meeting gate → IDLE   [v2: was missing]
  └── contradicting reward-burst signal detected → IDLE   [v2: see §9]

PROMPTED
  ├── START → ACTIVE
  ├── IGNORE → SUPPRESSED
  └── expiry (no response) → IDLE

ACTIVE
  ├── idle threshold → PAUSED
  ├── manual pause → PAUSED
  └── STOP SESSION → COMPLETE

PAUSED
  ├── single qualifying event → ACTIVE   [v2: resume threshold = 1 event, distinct from start threshold]
  ├── manual resume → ACTIVE
  └── STOP SESSION → COMPLETE

SUPPRESSED
  └── cooldown elapsed (default 10 min) OR qualifying-activity gap resets → IDLE   [v2: was a dead end]

COMPLETE
  ↓
finalise → persist → IDLE
```

**`[v2]` Fixes applied:**
- **CANDIDATE had no exit back to IDLE.** If a player logs two qualifying XP drops and then stops (gap too large, or the buffer window elapses before the confidence gate is met), the original diagram left the state machine stuck in CANDIDATE indefinitely. CANDIDATE now explicitly times out to IDLE, discarding the buffer per §10.
- **SUPPRESSED had no exit at all.** Ignoring one Fishing prompt would have permanently disabled future Fishing detection for the rest of the client session. SUPPRESSED now expires back to IDLE after a cooldown (default 10 minutes, configurable) or immediately if the player's activity pattern changes (a different skill starts producing qualifying events), so an IGNORE decision doesn't silently disable detection forever.
- **Resume threshold is explicitly decoupled from start threshold.** PAUSED → ACTIVE fires on a single qualifying event. Re-running the full 3–5-event confidence gate on every resume (as a naive reuse of `CandidateDetector` would do) causes missed or delayed resumes with no anti-noise benefit, since the session's legitimacy was already established once.
- `CandidateDetector` only evaluates events while state is `IDLE` or `CANDIDATE` for a given tracking group (§7a). Once `ACTIVE`/`PAUSED`, it is fully suppressed for that group — no re-prompting mid-session.

## 7a. TRACKING GROUPS (MULTI-SKILL SESSIONS) `[v4]`

Some activities gain XP in more than one skill at once — every combat hit awards Hitpoints XP alongside the combat-style XP, and a Slayer task adds Slayer XP on top. A session must represent this without either (a) treating each skill as an independent session — it's one activity — or (b) picking one skill and silently discarding the others' XP. As originally written, §9's reward-burst filter would have read this simultaneous gain as a multi-skill reward burst and permanently blocked combat/Slayer detection; this section is the fix.

Define a **tracking group**: a fixed set of skills whose simultaneous XP gain is normal for a single activity, not a reward burst.

```
COMBAT_GROUP = { Attack, Strength, Defence, Ranged, Magic, Hitpoints, Slayer }
```

Every skill not in a defined group is its own singleton group — Fishing is its own group, and gaining Fishing + Woodcutting XP simultaneously is still two distinct groups, not concurrent.

Each skill maps to a **group key**, the group's canonical identity, used everywhere a single "session skill" was previously assumed:

```
groupKey(skill) = SLAYER   if skill ∈ COMBAT_GROUP
groupKey(skill) = skill    otherwise
```

This one function is the entire mechanism:

- **§9's reward-burst filter** compares distinct *group keys* changing in a tick, not distinct skills. Attack+Hitpoints+Slayer landing in the same tick collapse to one group key (`SLAYER`) — normal combat, not a burst. Mining+Smithing+Crafting landing together stay three distinct group keys — still correctly rejected as a reward pattern.
- **Candidate buffering, suppression, and session identity** (§7, §8) key on group key rather than raw skill — one `CandidateBuffer`/`ActivitySession` per group key in flight at a time, exactly as before, just with the combat skills folding into one bucket.
- **`ActivitySession`'s skill field is the group key**, not necessarily the literal skill that triggered detection. For a skilling session it's the skill itself; for a combat session it's always `SLAYER`. This is also what the headline XP/hour rate (§14) is computed from — Slayer XP/hour for combat sessions — while `xpGained` (already a per-skill map, §44) still captures the real Attack/Strength/Ranged/Magic/Hitpoints/Slayer split underneath. Nothing is discarded; the group key only drives detection, identity, and the headline number.
- **Combat-group detection requires Slayer XP specifically**, not just any combat-group skill — this is what §1a's task-only scope decision compiles down to mechanically: the confidence gate for `groupKey = SLAYER` only fires once the buffer contains at least one actual Slayer XP event, not merely repeated Attack/Strength/Hitpoints hits.
- The Start/Ignore prompt's `+XXX XP` figure (§6) sums every event in the buffer regardless of which real skill it came from, so for a combat candidate it's a combined Attack+Strength+Hitpoints+Slayer total — an acceptable simplification for a one-line prompt. The live session view and history (§11, §37) break the total out correctly per skill via `xpGained`.

No other cross-skill concurrency is currently defined. If a future activity needs its own group (e.g. a minigame that reliably awards two skills at once), add it here rather than special-casing it elsewhere.

## 8. SESSION START DETECTION

v0.1 should use simple deterministic rules. Initial suggested confidence gate:
- same skill
- approximately 3–5 XP drops
- within approximately 60–90 seconds
- sufficiently spaced to resemble repeated actions (minimum ~2 seconds between drops, to reject a burst that lands in one or two ticks)

Tune through real gameplay. Avoid over-engineered detection.

## 9. REWARD FILTERING `[v2][v4]`

Reject obvious multi-skill/reward patterns — precisely, distinct *tracking-group keys* (§7a) changing in the same instant, not distinct raw skills; combat's simultaneous Attack/Hitpoints/Slayer gain collapses to one group key and is not a burst. Example, within the same instant:

```
+10,000 Mining
+10,000 Smithing
+10,000 Crafting
    ↓
reward pattern → no session prompt
```

Repeated, over time:

```
+35 Fishing
+35 Fishing
+35 Fishing
+35 Fishing
    ↓
probable activity → prompt
```

**`[v2]` Same-skill reward bursts are not caught by the multi-skill rule above.** Some quests and minigames grant several same-skill XP drops in quick succession as separate reward lines (e.g. three `StatChanged` events for Fishing fired across one quest-completion sequence) — this can satisfy the naive "same skill, 3–5 drops, within 90s" gate and false-positive as a real activity. Add a source-correlation check: an XP event is excluded from candidate buffering (regardless of skill or count) when it lands in the same game tick as a quest-completion widget, a reward-message chat event, or while a non-gameplay interface (quest reward screen, minigame reward screen) is open. This is a cheap, deterministic signal available from existing `ScriptPostFired`/`ChatMessage`/`WidgetLoaded` events and closes the false-positive path without adding statistical complexity.

## 10. RETROACTIVE BUFFER

Candidate mode temporarily records: timestamp, XP event, skill, optional activity signals, relevant direct output events.

```
buffered candidate → START pressed → ActivitySession
buffered candidate → IGNORE or expiry → discard buffer
```

Buffers must be bounded.

## 11. CURRENT SESSION UI

```
SKILLING INFO

FISHING
Karambwans

SESSION
─────────────────
Total            01:42:18
Active           01:31:04
Idle             00:11:14

Fishing XP        +71,420

Active XP/hr       47,075
Overall XP/hr      41,892

OUTPUT
─────────────────
Caught                 842
Dropped                 25
Picked up again          2
Banked                 819

Net retained           819

FUTURE XP
─────────────────
Cooking            +155.6k   (confirmed banked)

[ PAUSE ] [ STOP SESSION ]
```

*Sanity-checked: 71,420 / 5,464s × 3600 = 47,074 ≈ 47,075 active XP/hr; 71,420 / 6,138s × 3600 = 41,892 overall XP/hr — both match the displayed figures.*

## 12. SESSION CONTROLS

Required: `PAUSE`, `RESUME`, `STOP SESSION`.

- **Pause** — stops active-time accumulation.
- **Resume** — continues current session.
- **Stop Session** — finalises and stores the session.

Manual Stop is authoritative.

## 13. IDLE TIME

Track total time, active time, idle time. Auto-pause after configurable inactivity. Suggested default: 5 minutes.

Do not equate idle time with certainty that the user is physically AFK. It means: no qualifying activity detected during that interval.

`[v2]` The idle-to-pause timer and the CandidateDetector's buffer window (§8, §9) are independent clocks that happen to read the same underlying signal (qualifying activity timestamps). They must not share mutable state — implement as two separate consumers of a single "last qualifying activity" utility clock, not one timer feeding two decisions.

`[v3]` Auto-pause, not auto-end. Bossing Info (§5) times out straight to a completed session on inactivity, with no equivalent of PAUSED. Skilling Info deliberately pauses instead: the product's central question is "what did the account gain," and a player who steps away for six minutes mid-session hasn't gained less account value than one who didn't — ending the session there would undercount active time and force a fresh detection prompt on return for no reason. This is a considered choice, not a gap relative to the reference plugin.

`[v4]` Idle-reset is not XP-only. Once item-flow tracking exists (Phase 2+), a bank-open, pickup, or drop event also counts as qualifying activity for the idle clock, not just XP gain — otherwise a long loot-banking trip with no XP in the idle window would incorrectly auto-pause a session that's still actively in progress (a real scenario for Slayer, where banking between trips can easily exceed the default 5-minute threshold). `SessionManager.recordNonXpActivity()` is already wired for this in Phase 1, ready for the Phase 2+ correlators to call — it resets the idle clock exactly like an XP event would, including resuming a PAUSED session.

## 14. RATE DEFINITIONS

**Active XP/hour** = XP gained / active seconds × 3600
**Overall XP/hour** = XP gained / total seconds × 3600

Persist raw values. Calculated values may be regenerated.

## 15. GENERIC SKILLING FALLBACK

Every skill should support generic tracking even when exact activity detection fails.

```
WOODCUTTING
Activity: Unclassified
Active            41:12
XP              +62,710
Active XP/hr      91,325
```

Named methods are enhancements.

## 16. ACTIVITY CLASSIFICATION

Use conservative deterministic signals: XP, animation, object interaction, NPC interaction, region/location, game messages, varbits, varplayers, RuneLite events.

If uncertain: `Unclassified` is correct.

## 17. CORE ITEM LIFECYCLE

Every session maintains an `ItemFlow` model:

```
ItemFlow
├── generated
├── acquired
├── pickedUp
├── dropped
├── pickedUpAgain
├── consumed
├── transformed        [v2: see §18]
├── banked
└── retained
```

The plugin should preserve raw event history or sufficient aggregates to reconstruct these totals.

## 18. ITEM FLOW EVENT TYPES `[v2]`

```
ITEM_GENERATED
DIRECT_ACQUISITION
GROUND_PICKUP
ITEM_DROPPED
GROUND_REPICKUP
ITEM_CONSUMED
ITEM_TRANSFORMED     [v2: added — see below]
ITEM_BANKED
OTHER_INVENTORY_GAIN
OTHER_INVENTORY_LOSS
```

**`[v2]` `ITEM_TRANSFORMED` was missing** despite §54 requiring that item transformation (e.g. raw fish → cooked fish, logs → planks) not be misclassified as a drop-and-acquire pair. Populate it only from a static known-recipe table (cooking, fletching, smithing, herblore, etc., sourced from existing RuneLite plugin data where available): if itemId A decreases and itemId B increases in the same tick and (A→B) is a known recipe pair, emit `ITEM_TRANSFORMED` and exclude both sides from drop/acquire counting. Unmapped pairs are left as `OTHER_INVENTORY_LOSS` / `OTHER_INVENTORY_GAIN` rather than guessed.

Each event contains, where available: `timestamp`, `itemId`, `quantity`, `source`, `confidence`, `activitySessionId`.

## 19. ITEM PROVENANCE

Every acquisition should attempt to identify provenance. Supported conceptual sources:

```
SKILLING_OUTPUT
GROUND_PICKUP
ACTIVITY_REWARD
NPC_DROP_PICKUP
MINIGAME_REWARD
OTHER_INVENTORY_GAIN
```

Do not falsely classify unknown inventory gains.

`[v2]` Provenance is **session-scoped**, not spawn-event-scoped: the game does not reliably let you attribute one specific ground pickup to one specific kill when multiple sources of the same stackable item are live at once. See §20a for the attribution-confidence mechanism this requires.

## 20. GROUND PICKUP TRACKING

Ground pickups should be detected by correlating passive RuneLite signals. High-confidence model:

```
ground item exists
    +
player takes item / relevant menu event
    +
inventory quantity increases
    +
matching ground quantity decreases
    ↓
CONFIRMED GROUND PICKUP
```

Do not modify menu behaviour. Only observe.

### 20a. Ground Pickup Correlation Algorithm `[v2]`

The rule above describes the desired end state but not the failure path — a `MenuOptionClicked` "Take" fires client-side before the server confirms the pickup, so a click is not a guarantee of success (contested tile, full inventory, despawn race). Implement as a bounded pending-correlation ledger:

1. On `MenuOptionClicked` targeting a `TileItem` with a Take-style option: push `PendingPickup{itemId, tile, qtyAvailable, clickTick}` onto a per-itemId FIFO queue. `qtyAvailable` comes from `GroundItemTracker`'s live state (§20b), not the click event itself.
2. On each subsequent `ItemContainerChanged(INVENTORY)`: if the itemId delta is positive **and** the ground item at that tile decreased in the same or immediately following tick, pop the matching pending entry and mark `CONFIRMED_GROUND_PICKUP`. Same-tick match → `CONFIRMED`; match within the window but off-tick → `PROBABLE`.
3. **Timeout is mandatory.** If no matching inventory delta lands within a short fixed window (default 5 ticks / 3s), discard the pending entry. It must never be carried forward to match a *later* unrelated inventory increase of the same item — that would misattribute pickup #2 to pickup #1's stale click.
4. **Fail-fast on inventory-full:** listen for the `"Your inventory is full."` game message and immediately expire the matching pending entry rather than waiting out the timeout.
5. **Contested tile:** if the ground quantity decreases but our inventory does not change in the window, another player took it. Correctly produce no event.

### 20b. Ambiguous Stackable Provenance `[v2]`

Multiple identical stackable ground items from different sources on one tile (e.g. two NPCs both drop Coins) cannot be disambiguated from ground state alone. Rather than fabricate false precision, add `attributionConfidence: PER_DROP | SESSION_AGGREGATE` to each `ItemFlow` entry:

- Snapshot tile occupancy for the relevant itemId immediately before a kill/action resolves. If it was empty and exactly one plausible source exists (single-target kill, no adjacent pile), the resulting pickup is `PER_DROP`-attributable.
- If a pile of the same itemId already existed nearby, or multiple NPCs could plausibly have dropped it, attribution collapses to `SESSION_AGGREGATE` — the item is still counted, just not claimed against a specific kill.
- Coin piles and other structurally indistinguishable stacks are always `SESSION_AGGREGATE`.
- Per-monster loot-flow displays (§37) render only `PER_DROP` lines individually; everything else rolls into the session total. This makes §27's "undercount over false-attribute" principle an actual mechanism rather than a stated intent.

## 21. DROPPING ITEMS

Dropping is a first-class session event. High-confidence model:

```
Drop menu action
    +
inventory quantity decreases
    +
matching item appears/increases on ground
    ↓
CONFIRMED DROP
```

This applies equally to skilling drop methods, Slayer/PvM inventory management, making room for higher-priority loot, dropping supplies, discarding unwanted resources.

`[v2]` Use the same bounded pending-ledger pattern as §20a, mirrored: `MenuOptionClicked` (Drop) → pending entry → matched against inventory decrease + ground-item appearance within the timeout window, else discarded (not carried forward).

## 22. PICKING DROPPED ITEMS BACK UP

If an item previously dropped during the active session is later reacquired from the ground: `GROUND_REPICKUP`. This must not be counted as a new net account gain.

Example:

```
caught fish          +1
dropped fish         -1
picked fish up       +1
```

Final:

```
generated              1
dropped                1
picked up again        1
net retained           1
```

Do not double-count acquisition.

## 23. SKILLING DROP METHODS

Enables accurate tracking of activities where resources are routinely discarded.

```
TEAK TREES
Logs produced        618
Logs dropped         112
Logs picked up again   4
Logs banked          510

Net retained         510
Retention          82.5%
```

*Sanity-checked against §28/§32: 618 − (112 − 4) = 510 = confirmed banked; 510 / 618 = 82.5%. Formula, worked example, and displayed figure all agree.*

This is substantially more useful than simply recording "618 logs produced."

## 24. SLAYER INVENTORY MANAGEMENT

Example — player receives Rune arrows × 300. Inventory fills. Player drops Rune arrows × 150. Then picks up Ranarr seed × 1.

Session records:

```
Rune arrows
Acquired             300
Dropped              150
Retained             150

Ranarr seed
Picked up              1
Retained               1
```

This captures the player's real prioritisation decisions.

## 25. BANK CONFIRMATION `[v2]`

Opening the bank can provide the highest-confidence confirmation that retained resources made it into account storage.

```
item acquired
   ↓
remains associated with session
   ↓
bank opened
   ↓
inventory decreases + bank quantity increases
   ↓
CONFIRMED BANKED
```

### 25a. Bank Correlation Algorithm `[v2]`

The original rule described the desired signal but not a computable procedure, and interacted ambiguously with §26's separate "bank snapshot" concept. This replaces both with a single mechanism:

1. Maintain one `SessionOutstandingLedger: itemId → qty` per active session — the authoritative "acquired this session, not yet resolved" count. Incremented by `DIRECT_ACQUISITION`/`GROUND_PICKUP`; decremented by `ITEM_DROPPED`, `ITEM_CONSUMED`, and by confirmed `ITEM_BANKED` events as they resolve.
2. Maintain one running bank-contents snapshot (`itemId → qty`), updated on **every** `ItemContainerChanged(BANK)` event while the bank is open — not only at bank-close. This is the same snapshot referred to informally in §26; there is only one bank-state tracker in the system, not two.
3. On each such event, compute in the same tick:
   - `bankDelta[itemId]` = increase in bank quantity vs. the previous snapshot
   - `invDelta[itemId]` = decrease in inventory quantity vs. the previous tick
4. Attribute `candidateBanked[itemId] = min(bankDelta[itemId], invDelta[itemId], sessionOutstanding[itemId])`. This three-way minimum is the core safeguard: it caps attribution at what the session actually holds outstanding, and refuses to attribute a bank increase unless it is backed by a same-tick inventory decrease — ruling out coincidental deposits of pre-existing bank stock or unrelated bank activity.
5. Mark `candidateBanked` as `ITEM_BANKED` with `CONFIRMED` and decrement `sessionOutstanding` accordingly. Any leftover `bankDelta` (no matching outstanding balance, or no matching inventory decrease) is left unattributed — never recorded as session output. This is what makes §27's worked example (Death rune/Coal not attributed) a guaranteed outcome of the algorithm, not just a stated intention.
6. Withdrawals (inventory increase + bank decrease) never touch `sessionOutstanding` — the deposit signature requires an inventory decrease, so a withdraw-then-redeposit of pre-existing stock is naturally inert and cannot be misattributed.
7. Per-tick (not per-bank-session) diffing is required to correctly capture "Deposit All", partial deposits, and multiple deposit/withdraw actions within one bank visit — a single diff taken at bank-close would miss intermediate withdraw/redeposit sequences.

## 26. BANK SNAPSHOT SUPPORT `[v2]`

`[v2]` Folded into §25a: the running bank snapshot used for correlation *is* the lightweight `itemId → last seen quantity` memory described here in v1. There is a single bank-state tracker, reused for both live correlation and reconciliation/validation purposes (§52) — maintaining a second, independent snapshot would risk the two diverging after a missed event.

Uses: validation, session reconciliation, missed bank-event recovery, confirmed account-gain checks.

Do not depend directly on another plugin's private persistence format unless that plugin exposes a stable supported API.

## 27. BANK ATTRIBUTION

Do not treat every positive bank delta as session loot. Only attribute banked items where the current session has relevant preceding item-flow evidence.

Example — session observed Ranarr seed acquired × 4. Bank later increases: Ranarr seed +4, Death rune +500, Coal +200. If the session did not observe Death rune or Coal gains: do not attribute them automatically.

Prefer undercounting over false attribution. (Enforced mechanically by §25a step 5.)

## 28. NET RETAINED `[v2]`

```
Net retained
  =
session-acquired items
  − confirmed permanent discards
  − confirmed consumption
  ± repickup corrections
```

Where bank confirmation exists, `confirmed banked` becomes the strongest account-gain state.

**`[v2]` Reconciliation invariant (new — see §52):** when full correlation succeeds, `banked ≈ generated − dropped + repickup − consumed` should hold. This is a derivable cross-check, not an independent input — use it at finalisation to detect uncorrelated events rather than to compute the headline number.

## 29. ITEM LIFECYCLE DISPLAY

```
ITEM FLOW
─────────────────
Karambwan
Produced           842
Dropped             25
Picked up again      2
Banked             819

Net retained        819
```

The UI need not show all stages by default. Detailed session view may.

## 30. SESSION ECONOMICS

For PvM/Slayer:

```
Loot generated value
Loot picked up value
Loot dropped later
Loot banked value
```

Example:

```
Abyssal Demons
Generated loot       1.20m
Picked up             710k
Dropped later          22k
Confirmed banked       688k
```

This is a unique behavioural metric.

## 31. PICKUP RATE `[v2]`

**`[v2]` Formula corrected.** The v1 notation ("value/quantity acquired ÷ value/quantity generated") reads as a ratio of *per-unit* values, which would always be ≈1 for the same item and cannot produce a meaningful percentage. The intended metric — confirmed by the worked example already in the spec (§30: 710k / 1.20m = 59.2% ≈ the displayed 59%) — is a ratio of **totals**:

```
Pickup rate = (Σ value of items picked up) / (Σ value of items generated)
```

```
Loot pickup rate     59%
```

This describes how the player actually loots an activity. It is historical analytics, not advice.

## 32. RETENTION RATE

For gathering/skilling:

```
Retention rate = net retained / total generated
```

Example:

```
Logs produced        618
Net retained         510
Retention          82.5%
```

*(Verified consistent with §23/§28 — see sanity-check note there.)*

This is highly useful for Ironman planning.

## 33. FUTURE / BANKED XP `[v2][v4]`

`[v4]` Data source decision: build a small, hardcoded item→XP lookup table ourselves, maintained alongside Phase 4, rather than depending on `banked-experience`'s data (§5's competitive review) or shipping v0.1 with no XP projection at all. Keep it deliberately narrow — cover only the items the shipped activity modules (§40) actually produce (karambwan→Cooking, raw fish→Cooking, logs→Firemaking, ore→Smithing, and similar single-obvious-use cases). Unmapped items simply have no Future XP row rather than a guessed one, consistent with §35's "don't force downstream XP mappings" rule for ambiguous items like teak logs.

Resources may represent future XP. Prefer, in decreasing order of confidence:

```
CONFIRMED BANKED QUANTITY
RETAINED QUANTITY
ACQUIRED QUANTITY
```

**`[v2]` This ordering must be a first-class field, not just display language.** Add `confidenceLevel: CONFIRMED_BANKED | RETAINED | ACQUIRED_ONLY` to the Future XP data model. Default UI behaviour: only surface Future XP once confidence ≥ `RETAINED`; `ACQUIRED_ONLY` figures are hidden by default or shown clearly labelled "(estimated)" so no uncertain projection reads as earned XP. Without an explicit field, this rule has no enforcement point and risks silently degrading to "show whatever number we have."

Example:

```
Future Cooking XP
confirmed banked
+155.6k
```

Do not present uncertain potential as earned XP.

## 34. RAW DATA AUTHORITY

Always preserve `itemId`, `quantity`, `lifecycle stage` before derived calculations.

Example: raw karambwan confirmed banked = 819 is authoritative. Derived: Cooking XP = 155,610 can be recalculated later.

## 35. AMBIGUOUS RESOURCE USE

Do not force downstream XP mappings where one item has several legitimate uses. Example: teak logs may contribute to Construction, Firemaking, or Fletching. RuneLite should preserve `teak logs banked = 510`. External planning software can decide how to value them.

## 36. IRONMAN SESSION MODEL

```
KARAMBWANS
Fishing XP          +71,420
Active XP/hr         47,075

Produced                842
Dropped                  25
Repicked                  2
Confirmed banked         819

Net account gain
819 raw karambwan

Future Cooking XP
+155.6k
```

## 37. SLAYER SESSION MODEL

```
ABYSSAL DEMONS
Kills                   187
Kills/hr              151.2
Slayer XP           +28,417

LOOT FLOW
Generated value       1.20m
Picked up              710k
Dropped later           22k
Banked                  688k

ACCOUNT GAIN
Ranarr seed              +4
Death rune             +710
Blood rune             +382
Rune med helm            +5

Initial loadout
[ VIEW ]
```

`[v2]` Per-item ACCOUNT GAIN lines render only where `attributionConfidence = PER_DROP` (§20b); ambiguous stackables shown here are understood to be session-aggregate totals, not confirmed per-kill splits.

`[v4]` This model only exists at all because a Slayer task is active — §1a scopes combat sessions to Slayer-task combat, mechanically enforced by §7a's Slayer-XP-presence gate. Plain bossing without a task never reaches this screen.

## 38. STARTING INVENTORY / EQUIPMENT

For Slayer and selected activities, capture starting inventory and starting equipment at the first meaningful activity point. Do not continuously snapshot unless required for a specific feature.

## 39. TRIPS `[v2]`

Session architecture should support:

```
Session
   ├── Trip 1
   ├── Trip 2
   └── Trip 3
```

A bank visit can become a natural trip boundary later:

```
combat activity → bank → confirm item flow → new inventory → next trip
```

Do not require automatic multi-trip modelling in v0.1.

**`[v2]` Pre-wire the data model now, defer the UI/aggregation.** §37's Slayer loot-flow model already treats a bank visit as an implicit resolution boundary, and Phase 6 (§61) will need trip segmentation for KPH/loadout tracking. Rather than risk a schema migration later, record a lightweight `tripBoundaries: [timestamp]` list on the session object from Phase 1 onward, appended on each bank-open while `ACTIVE` (this is a free byproduct of §25a's bank-open hook). Trip *aggregation and display* remain out of scope until Phase 6.

## 40. ACTIVITY OUTPUTS

Activity modules may expose:

**Fishing** — catches, catches/hr, dropped, banked, future Cooking XP
**Woodcutting** — logs, logs/hr, dropped, banked, retention
**Mining** — ores, ores/hr, dropped, banked
**Agility** — laps, laps/hr, marks
**Mahogany Homes** — contracts, contracts/hr
**Slayer** — kills, KPH, loot picked up, loot banked, loadout

## 41. SIDEBAR `[v6]`

Primary views: `CURRENT`, `HISTORY`. Future: `ACTIVITIES`.

`[v6]` Superseded by §65's skill-icon toggle row — reached via a persistent "current" icon plus one icon per skill in history, not a two-button text tab switch. `ACTIVITIES` isn't a separate future view under this model; a per-activity breakdown (Phase 5) is a natural extension of the existing per-skill filter rather than a third navigation mode.

## 42. CURRENT PANEL

Core priority order: activity → session time → XP/rate → activity output → item flow → future XP → controls.

Avoid clutter.

## 43. HISTORY

Completed sessions persist.

```
TODAY

Karambwans
1h 31m active
47.1k XP/hr
819 banked
+155.6k future Cooking XP

Teaks
39m active
110.1k XP/hr
510 banked

Abyssal Demons
187 kills
151.2 KPH
688k loot banked
```

## 44. STRUCTURED LOCAL OUTPUT `[v2][v3][v4]`

One completed-session record per line. Preferred: JSON Lines (`sessions.jsonl`).

`[v4]` `"skill"` and `"category"` reflect tracking-group semantics (§7a/§1a): for a combat session `skill` is `"SLAYER"` and `category` is `"COMBAT"`, even though `xpGained` also holds Attack/Strength/Ranged/Magic/Hitpoints entries. `category` is derived from `skill`, not stored independently, so the two can never drift apart.

`[v3]` Storage must be scoped per account, not a single global file. Bossing Info (§5) learned this the hard way — it keys its data directory by `client.getAccountHash()` (migrating from an older username-keyed layout), because otherwise a main/alt or multiple GIM members sharing a machine would merge their history. Skilling Info's `sessions.jsonl` lives under `skilling-info/<accountHash>/`, resolved lazily since the account hash isn't valid until after login (implemented in `SessionRepository`).

Conceptual session record:

```json
{
  "schemaVersion": 1,
  "id": "...",
  "category": "SKILLING",
  "skill": "FISHING",
  "activity": "KARAMBWANS",

  "startedAt": "...",
  "endedAt": "...",

  "totalSeconds": 6138,
  "activeSeconds": 5464,
  "idleSeconds": 674,

  "tripBoundaries": ["..."],

  "xpGained": {
    "FISHING": 71420
  },

  "itemFlow": [
    {
      "itemId": 3142,
      "generated": 842,
      "directlyAcquired": 842,
      "pickedUp": 0,
      "dropped": 25,
      "repicked": 2,
      "consumed": 0,
      "banked": 819,
      "netRetained": 819,
      "attributionConfidence": "SESSION_AGGREGATE"
    }
  ]
}
```

`[v2]` The v1 schema example omitted `pickedUp`, `consumed`, `tripBoundaries`, and `attributionConfidence` despite these being defined elsewhere in the spec (§17, §18, §20b, §39) — added for consistency between the data model narrative and the persisted schema. Raw counts are authoritative.

## 45. EVENT LOG VS SESSION LOG

Optionally maintain two layers: a raw/internal event representation (useful for debugging and session assembly) and a completed session log (primary external interface). Prefer external consumers to read completed sessions rather than replay every RuneLite event.

## 46. NO NETWORK REQUIREMENT

v0.1 should use: no HTTP, no webhook, no local server, no socket, no cloud API, no developer backend. Everything remains local.

## 47. COMPLIANCE PRINCIPLE

Passive observation only. Skilling Info must not: invoke game actions, alter menu actions, simulate clicks, simulate keys, automate prayers, automate equipment, automate looting, make gameplay decisions, provide prohibited combat guidance, launch processes, use JNI, use reflection, collect credentials, crowdsource other players.

Listening to menu/inventory/ground-item events is analytical observation only.

## 48. IMPLEMENTATION ARCHITECTURE `[v4]`

```
SkillingInfoPlugin
    │
    ├── CandidateDetector
    ├── CandidateBuffer
    ├── TrackingGroups          (§7a — groupKey() used by SessionManager)
    │
    ├── SessionManager
    ├── SessionClock
    ├── XpTracker
    │
    ├── ActivityClassifier
    ├── ActionTracker
    │
    ├── ItemFlowTracker
    │    ├── InventoryDeltaTracker
    │    ├── GroundItemTracker
    │    ├── PickupCorrelator      (§20a)
    │    ├── DropCorrelator        (§21)
    │    ├── RepickupCorrelator    (§22)
    │    └── BankCorrelator        (§25a — single bank snapshot owner, §26)
    │
    ├── RetentionCalculator
    ├── FutureXpResolver
    │
    ├── SessionRepository
    ├── SessionExporter
    │
    └── SkillingInfoPanel
```

Later: `SlayerTracker`, `LoadoutSnapshotService`, `TripTracker`.

## 49. EVENT CORRELATION

Do not classify from a single ambiguous event where multiple passive signals are available.

Example pickup: `MenuOptionClicked + ItemContainerChanged + ground item reduction → confirmed pickup`
Example drop: `MenuOptionClicked + inventory reduction + ground item increase → confirmed drop`

Use bounded short-lived correlation windows (§20a, §21).

## 50. CONFIDENCE MODEL `[v4]`

Recommended internal confidence: `CONFIRMED`, `PROBABLE`, `UNKNOWN`.

Only headline statistics should use CONFIRMED where precision matters. Preserve unknown inventory movements separately instead of forcing classification.

`[v4]` Confirmed decision: `OTHER_INVENTORY_GAIN`/`OTHER_INVENTORY_LOSS` events (§18) are never surfaced to the user, not even as a count — consistent with §27's "prefer undercount over false attribution." They exist purely as an internal safety valve and a debugging aid (§45), not a UI element.

## 51. ONE SOURCE OF TRUTH

Use one `ActivitySession` model for live UI, history, persistence, structured export. Item flow belongs directly to that session model.

## 52. FINALISATION PIPELINE `[v2]`

```
STOP SESSION
   ↓
stop clock
   ↓
finalise XP
   ↓
finalise actions
   ↓
resolve pending item correlations
   ↓
calculate item lifecycle
   ↓
calculate retained/banked output
   ↓
calculate reliable future XP
   ↓
validate                              [v2: see reconciliation check below]
   ↓
persist
   ↓
append session record
   ↓
update History
   ↓
clear active session
```

**`[v2]` "Validate" is now a concrete step, not a placeholder.** For each itemId, check the reconciliation invariant from §28: `banked ≈ generated − dropped + repickup − consumed`. A mismatch indicates uncorrelated events (e.g. items still sitting in inventory unbanked at session end, or a missed bank event) — this is expected and not an error condition. Represent the shortfall as `retained (unbanked)` rather than either forcing it into `banked` or discarding it silently.

## 53. TESTING — PICKUPS

Test: one ground item pickup, stackable pickup, partial stack pickup, multiple items same tile, full inventory, item pickup while another inventory item changes, ground-item despawn unrelated to player, delayed inventory change, pickup followed by immediate drop.

## 54. TESTING — DROPS

Test: single item drop, stack drop, partial stack drop, drop to make inventory room, drop followed by pickup, repeated drop/pick cycle, unrelated inventory loss, item transformation, consumption not misclassified as drop.

## 55. TESTING — BANKING

Test: direct deposit, deposit all, stackable deposit, partial deposit, bank withdrawal, unrelated bank delta, session item deposit, item picked up then dropped before banking, item dropped then repicked then banked, repeated trips.

Do not attribute unrelated bank changes to the session.

## 56. TESTING — SKILLING FLOW

Example fishing test: catch 100, drop 10, repick 2, bank 92.

Expected: `generated = 100, dropped = 10, repicked = 2, banked = 92, net retained = 92`. No double counting.

## 57. TESTING — SLAYER FLOW

Example: monster drops 10 rune items, player picks 6, player drops 2 later, player banks 4.

Expected: `generated = 10, pickedUp = 6, droppedAfterPickup = 2, banked = 4`. Headline account gain: `4`.

## 58. README ADDITIONS

README must explicitly explain: generated loot, actual pickups, dropped items, repickups, confirmed banked items, net retained output, why gross loot differs from account gain. Include worked examples.

## 59. REQUIRED SCREENSHOTS

- Item lifecycle session — show produced, dropped, picked up again, banked.
- Slayer loot flow — show loot generated, loot picked up, loot dropped later, confirmed banked.
- Session detail — show item lifecycle for at least one item.

## 60. PLUGIN HUB DESCRIPTION

Recommended: "Tracks personal skilling sessions, XP rates, idle time, activity output, actual pickups, dropped items and resources retained."

## 61. DEVELOPMENT PHASES

**Phase 1** — plugin shell; Bossing Info-style sidebar; candidate XP detection; Start/Ignore; XP; active/idle/overall time; Pause/Resume/Stop; history. Include `tripBoundaries` field in the session schema even though unused (§39).

**Phase 2** — direct skilling output; inventory delta engine; confirmed drops; retained output. Use Fishing or Woodcutting as validation.

**Phase 3** — ground-item tracker; confirmed pickup correlation (§20a); repickup handling.

**Phase 4** — bank correlation (§25a); confirmed banked output; banked/future XP.

**Phase 5** — activity modules.

**Phase 6** — Slayer; KPH; task loadouts; loot flow; trip support (activates the `tripBoundaries` field pre-wired in Phase 1).

**Phase 7** — documentation; screenshots; tests; Plugin Hub release.

## 62. PRODUCT NORTH STAR

The long-term dataset should answer: How fast do I actually train? How much time do I spend idle? What resources do I actually produce? What loot do I actually pick up? What do I discard? What do I ultimately bank? What is my real retention rate? What KPH do I actually achieve? Which loadouts work best for me? How much future XP does a session create? How long will my goals take based on how I personally play?

## 63. FINAL DIFFERENTIATOR

Dink / traditional loot tracking: What dropped?
XP trackers: How much XP was gained?
Boss trackers: How fast were the kills?
Skilling Info: What did I do, how efficiently did I do it, what did I choose to keep, and what actually made it back into my account?

That is the product identity.

## 64. FINAL IMPLEMENTATION RULE

Preserve the event chain.

```
For items:      generated → acquired → dropped → repicked → banked
For XP:         raw XP events → session XP → rate
For activities: raw signals → classification
```

Always store the raw fact before the derived interpretation.

The plugin's responsibility is: **accurately record how the session changed the player's account.**

## 65. UI/UX LOCK-DOWN `[v5]`

What RuneLite actually restricts, versus what's just our own discipline to nail down before Phase 5+ writes real UI code, are two different things — worth being precise about which is which.

**Genuine RuneLite/Plugin Hub constraints (verified against source, not assumed):**
- The sidebar container (`PluginPanel`) is a fixed width — every mockup in this spec (§6, §11, §29, §36, §37, §43) must be designed to fit vertically in a narrow column with no horizontal scrolling, not as a flexible-width panel.
- Must render correctly in both RuneLite's light and dark themes — use `net.runelite.client.ui.ColorScheme` constants throughout (already the convention in `CurrentView`/`HistoryView`), never a hardcoded hex colour, and check both themes before calling a screen "done."
- The config screen is auto-generated from the `Config` interface (`@ConfigItem`/`@ConfigSection`) — there is no hand-designed settings UI to mock up; §65's lock-down applies to the sidebar panel and any overlay, not config.
- Bundled image resources should be loaded via `getResourceAsStream()` (i.e. `ImageUtil.loadImageResource`), not `getResource()` — already how the codebase should load a bundled icon once one exists (§66).
- No custom look-and-feel, native rendering, or canvas manipulation beyond the standard `Overlay`/`OverlayPanel` classes registered through `OverlayManager` — ties back to §47's compliance boundary, not just a style preference.

**Beyond that, RuneLite deliberately imposes little else** — Plugin Hub review does not evaluate UI quality or usefulness (§5's earlier finding). Everything past the bullets above is Skilling Info's own design discipline to lock down, not an external rule:

- **No overlay in v0.1 — sidebar-only, decided.** Bossing Info-style plugins commonly ship one (in-game KPH/session infobox), but keeping v0.1 sidebar-only avoids extra `Overlay`/`OverlayManager` surface area before the core item-flow engine (Phases 2–4) is proven. Revisit once that's solid.
- **The ASCII mockups in this spec are wireframes, not a locked design.** Before Phase 5 (activity modules) starts adding per-activity panel variants, the UI states already implied — idle, prompted, active/paused session, history list, session detail — should be finalized as an explicit state-by-state spec (exact copy, exact field order, exact truncation/overflow behaviour for long activity names) so implementation doesn't thrash. The current scaffold's `CurrentView`/`HistoryView` already implement the idle/prompt/active states from §6/§11; history's detail view (§29's item-flow breakdown) doesn't exist yet and is the next one worth locking down before Phase 2 needs it.

**`[v6]` Navigation decided from live playtest feedback, not a mockup.** §41's "Primary views: CURRENT, HISTORY" is superseded — a plain text-button tab switch tested as too text-heavy and hid history behind an unlabelled second tab. Replaced with a skill-icon toggle row: a persistent "current session" icon plus one small icon per skill that has history, built on RuneLite core's `SkillIconManager.getSkillImage(Skill, boolean small)` (the same API `XpInfoBox` and `CompactBoostsOverlay` use). Clicking a skill icon filters `HistoryView` to that skill only, rather than one mixed chronological list — a direct, deliberate departure from Bossing Info's model of one boss's stats at a time reached through an unclear caret. All labels/buttons across `CurrentView`/`HistoryView` now use `FontManager.getRunescapeSmallFont()` — the earlier scaffold never set an explicit font and fell back to the oversized OS default, which is what actually prompted this pass (default-Swing-font text at native size reads as "massive" in a fixed ~225px sidebar next to RuneLite's own compact chrome).

## 66. DEPLOYMENT ARTIFACTS `[v5]`

Verified against RuneLite's plugin-hub submission docs directly. Status as of this scaffold:

| Artifact | Requirement | Status |
|---|---|---|
| `LICENSE` | BSD 2-Clause "Simplified" specifically — not just any OSI license | Added, copyright holder Centipedejoker |
| `runelite-plugin.properties` | `displayName`, `author`, `description`, `tags`, `plugins`, `build` (`standard` for the common case), `version` (optional — commit hash used if blank) | Present, `author=Centipedejoker` |
| Repo-root `icon.png` | Optional, ≤48×72px — the Plugin Hub *listing* thumbnail | **Missing.** Distinct from the in-sidebar `NavigationButton` icon (§65) — two separate assets, both need real design work, not the placeholder circle-and-letters the scaffold currently draws programmatically |
| `README.md` | Feature description; §58 already specifies the required content (generated/acquired/dropped/repicked/banked, why gross loot differs from account gain) | Present, satisfies §58 |
| Screenshots | §59 requires three: item-lifecycle session, Slayer loot flow, session detail | **Cannot exist yet** — they must be real captures from a running client, not mockups; blocked on Phase 2 (item lifecycle) and Phase 6 (Slayer) actually being built |
| Repository structure | Public GitHub repo, ideally matching [runelite/example-plugin](https://github.com/runelite/example-plugin)'s generated layout | Scaffold follows the standard `build.gradle`/`settings.gradle`/`src/main/java` shape; worth a direct diff against the template before submission to catch drift |
| Plugin Hub pointer file | A PR to `runelite/plugin-hub` adding `plugins/<internal-name>` containing `repository=<git URL>` and the full 40-character `commit=<hash>` of the version being published | Not applicable until there's a tagged, working release to point at — this is the actual "deployment" step, distinct from writing code |
| Dependency verification | Non-transitive third-party dependencies need Gradle's cryptographic dependency-verification set up | Not currently needed — the scaffold only takes `compileOnly` on `runelite-client` and `lombok`; revisit if a later phase adds a real third-party library (e.g. a charting dependency) |

**Remaining before either section can be called fully locked:**
1. A real repo-root `icon.png` (≤48×72px) and a real `NavigationButton` sidebar icon — both still placeholder/programmatic, both genuine design work rather than something to spec further in prose.
2. §59's three required screenshots — blocked on Phase 2 and Phase 6 actually existing to screenshot.
3. The per-state UI wireframe lock-down described above (exact copy/field order/overflow behaviour), specifically the session-detail item-flow view that doesn't exist yet.

## 67. ARTIFACT OWNERSHIP `[v5]`

Who produces what, and why the split falls where it does — the deciding factor throughout is simply what each side can actually do: code/docs/tests are produced here; anything needing a live OSRS account, a real RuneLite client, creative/design judgment, or a GitHub-side action belongs to the project owner.

**Produced here (code, spec, docs):**
- `SPEC.md` and all its revisions
- Full Java plugin source across every phase — session state machine, item-flow engine, activity classifiers, `SlayerTracker`, UI panels
- Project scaffold: `build.gradle`, `settings.gradle`, `runelite-plugin.properties`, `.gitignore`
- `README.md` (§58's required content) and `LICENSE` (template — done, holder: Centipedejoker)
- Unit tests derived from §53–57's test plan
- UI wireframe lock-down (§65) — exact per-state copy/layout, as a spec artifact or a rendered mockup

**Produced by the project owner (assets, live verification, publishing):**
- Repo-root `icon.png` (≤48×72px) and the sidebar `NavigationButton` icon — real design work, not something specifiable in prose
- Local dev environment (JDK, IDE, a RuneLite client checkout or sideload setup) and the first successful compile — this environment has no `gradle` install and no verified access to `repo.runelite.net`, so the build has only been reviewed by hand, never actually run
- In-game manual testing against real gameplay (§53–57's scenarios), and tuning §8's detection thresholds (candidate drop count/window/spacing) against how it actually feels to play — both require a live OSRS account
- §59's three required screenshots, captured from an actual running session
- Creating and hosting the public GitHub repository, pushing commits, tagging releases
- The `runelite/plugin-hub` submission PR (the pointer file: `repository=` + `commit=`) and responding to review feedback
- Ongoing update PRs (new commit hash each release)

Bug reports or behaviour that doesn't match the spec, found during live testing, feed back here as fixes — that loop is expected to run more than once before a Plugin Hub submission is worth opening.
