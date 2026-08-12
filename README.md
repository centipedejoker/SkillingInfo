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

## What it tracks

**Per session**
- Active, idle and total time, with auto-pause when you stop
- XP, XP/hour, actions and actions/hour — all scoped to *this* session
- Retention rate: what share of what you produced you actually kept
- Every item's full lifecycle, and the projected XP your banked resources represent

**Across sessions**
- A per-skill all-time summary — time, XP, banked XP
- A running per-item tally of what the account has actually accumulated
- Every completed session, expandable to its full lifecycle ledger

**Activities it can name**

Woodcutting, Fishing, Mining, Hunter, Farming, Cooking, Smithing and
Runecraft are identified by what they produce ("Oak trees", "Fly fishing",
"Runite ore"). Slayer sessions are named by the task itself, taken from
RuneLite's own Slayer plugin.

Any skill not in that list still tracks completely — time, XP, rates, item
flow — it just shows as "Unclassified" rather than a named method. That's a
deliberate outcome, not a gap: guessing an activity from ambiguous evidence
would be worse than admitting it isn't known.

**Slayer**

Kills and kills/hour scoped to the session, plus the loot-flow story: what
dropped, what you actually picked up, what you dropped again later, and what
was banked. For combat the retention figure is labelled *pickup rate*, since
it answers a different question — what share of the loot you bothered to
take.

## Where your data lives

One JSON Lines file per account:

```
~/.runelite/skilling-info/<accountHash>/sessions.jsonl
```

One completed session per line, appended and never rewritten. Raw item
counts are stored as the authoritative record, so an external tool can
recompute anything derived from them.

Projected XP is the exception: it's resolved when the session ends and then
frozen. If you later decide your iron ore is destined for steel bars rather
than iron, that applies to future sessions — it does not silently rewrite
what past sessions reported.

Nothing is sent anywhere. There is no HTTP, no socket, no telemetry.

## Running locally

This is a RuneLite external plugin, tested by launching a real RuneLite
client with it pre-loaded — there's no way to see it working without that.

**One-time setup:**

1. Install a JDK — version 11 or newer (RuneLite's minimum).
   ```bash
   brew install openjdk@17
   sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
   ```
   The `sudo` step makes `java`/`./gradlew` find it system-wide. Verify with
   `java -version`.
2. The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) is
   already committed — no separate Gradle install needed.
3. Open the project folder in IntelliJ IDEA (Community Edition is fine) and
   let it import the Gradle project — this resolves `net.runelite:client`
   from `https://repo.runelite.net` automatically, no local RuneLite
   checkout needed.
4. **Jagex account login** — a locally-built client can't do the Jagex
   Launcher's OAuth flow itself, so it borrows credentials from one that
   can ([RuneLite wiki: Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)):
   1. Open the official Jagex Launcher (2.6.3+), edit the RuneLite entry,
      add `--insecure-write-credentials` to its Client arguments.
   2. Launch RuneLite from the Jagex Launcher with that flag and log in
      normally once — this writes `~/.runelite/credentials.properties`.
   3. `./gradlew run` (below) will pick that file up automatically.
   4. That file can log into your account directly, bypassing your
      password — don't share it, and delete it when you're done testing
      (or use "End sessions" under runescape.com account settings to
      invalidate it).

**Every time you want to test a change:**

```bash
./gradlew run
```

This compiles the plugin and launches a full RuneLite client with Skilling
Info already loaded alongside every core plugin (`SkillingInfoPluginTest`,
the standard `runelite/example-plugin` pattern — not a JUnit test despite
the name/location). Log in and check the sidebar for the Skilling Info
icon.

From IntelliJ instead: right-click
`src/test/java/com/skillinginfo/SkillingInfoPluginTest.java` → **Run**. Same
effect, with breakpoints available.

**macOS note:** the `run` task already passes
`--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED` — without it,
RuneLite's `OSXFullScreenAdapter` hits a Java 17 module-access error and
the client fails to open at all. If you're not using the `run` task
directly (e.g. a custom IDE run configuration), add that JVM arg yourself.

`./gradlew build` also runs the unit tests, which cover the parts that fail
silently rather than loudly: bank-deposit attribution, the session-detection
gate, equipment that grants two skills at once, and the guarantee that
changing a product selection never rewrites a recorded session.

## Compliance

Skilling Info only observes RuneLite events — XP, game ticks, and
inventory, equipment, bank and ground-item changes. It never sends input,
clicks or menu actions, never alters menus, and makes no network requests of
its own. It uses no reflection, spawns no processes, and reads no
credentials. See `SPEC.md` §47.

It builds on RuneLite's own plugins rather than duplicating them: task state
comes from the Slayer plugin, and monster drops from the Loot Tracker.

## Documentation

- `SPEC.md` — the full product specification and design record, including
  why particular decisions were made and where implementation forced a
  rethink.
- `HANDOVER.md` — project state, how to work on it, and the traps that
  already cost time once. Read this first if you're picking the code up.
- `DESIGN_BRIEF.md` — the brief the sidebar layout was designed against.
