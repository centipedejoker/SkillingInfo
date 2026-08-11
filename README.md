# Skilling Info

A passive RuneLite analytics plugin that answers a different question than a
loot tracker or an XP tracker: **after playing this activity this way for
this long, what did my account actually gain?**

It tracks performance (XP, rates, active/idle time) the way the XP Tracker
does, but it also follows every item through its full lifecycle for the
session:

```
generated → acquired → dropped → picked up again → banked
```

## Why "generated" isn't "banked"

A traditional loot tracker reports what dropped. Skilling Info reports what
you actually kept. Example — a Slayer monster drops:

```
Ranarr seed × 4
Death rune × 700
Rune med helm × 7
```

You pick up all of it, then later drop 2 Rune med helms to make room, then
bank everything else:

```
GENERATED         Ranarr seed 4, Death rune 700, Rune med helm 7
ACQUIRED           (picked up from the ground)
DROPPED           Rune med helm 2
CONFIRMED BANKED  Ranarr seed 4, Death rune 700, Rune med helm 5
NET RETAINED      Ranarr seed 4, Death rune 700, Rune med helm 5
```

The "5 Rune med helms" figure — not 7 — is what actually made it back into
your account. That distinction is the entire point of the plugin.

Terms used throughout the panel and the exported data:

- **Generated** — produced by the activity (skilling output, monster drop),
  whether or not you took it.
- **Acquired / picked up** — actually entered your inventory.
- **Dropped** — deliberately discarded from your inventory during the
  session.
- **Picked up again** — a previously-dropped item you re-collected; this is
  never counted as new net gain, only as a correction.
- **Confirmed banked** — observed moving from inventory into your bank
  during the session. The highest-confidence account-gain state.
- **Net retained** — your best estimate of what you actually kept,
  reconciled from generated/dropped/repicked/consumed and, where available,
  confirmed banked quantity.

Gross loot and account gain routinely disagree, sometimes by a lot — that's
the number this plugin exists to surface.

## Status

Phase 1 scaffold (see `SPEC.md` §61):

- [x] Plugin shell, sidebar (Current / History)
- [x] Candidate XP-drop detection with Start / Ignore prompt
- [x] Session state machine (idle → candidate → prompted → active/paused →
      complete → suppressed)
- [x] XP, active/idle/overall time, active & overall XP/hour
- [x] Pause / Resume / Stop
- [x] Local JSON Lines history, scoped per account
      (`~/.runelite/skilling-info/<accountHash>/sessions.jsonl`)
- [ ] Item-flow tracking (generated/acquired/dropped/repicked/banked) —
      Phases 2–4
- [ ] Activity classification, Slayer loot flow, trips — Phases 5–6

Full product spec and design rationale: [`SPEC.md`](SPEC.md).

## Running locally

This is a RuneLite external plugin, tested by launching a real RuneLite
client with it pre-loaded — there's no way to see it working without that.

**One-time setup:**

1. Install a JDK — version 11 or newer (RuneLite's minimum).
   ```bash
   brew install openjdk@17
   ```
2. Install Gradle, then generate the wrapper (not committed to this repo yet):
   ```bash
   brew install gradle
   cd "Skilling Info"
   gradle wrapper
   ```
   This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/` — commit
   them once generated so `./gradlew` works without a system Gradle install
   from then on.
3. Open the project folder in IntelliJ IDEA (Community Edition is fine) and
   let it import the Gradle project — this resolves `net.runelite:client`
   from `https://repo.runelite.net` automatically, no local RuneLite
   checkout needed.

**Every time you want to test a change:**

```bash
./gradlew run
```

This compiles the plugin and launches a full RuneLite client with Skilling
Info already loaded alongside every core plugin (`SkillingInfoPluginTest`,
the standard `runelite/example-plugin` pattern — not a JUnit test despite
the name/location). Log in with a real or alt OSRS account, open the
sidebar, and the Skilling Info icon should be there.

From IntelliJ instead: right-click
`src/test/java/com/skillinginfo/SkillingInfoPluginTest.java` → **Run**. Same
effect, with breakpoints available.

**If it doesn't compile or launch:** that's expected the first time — this
scaffold has been reviewed by hand but never actually built (§67 of
`SPEC.md`). Send me the compiler error or stack trace and I'll fix it.

## Compliance

Skilling Info only observes RuneLite events (`StatChanged`, `GameTick`,
inventory/ground-item/bank container changes). It never sends input, clicks,
or menu actions, and makes no network requests — everything is local. See
`SPEC.md` §47.
