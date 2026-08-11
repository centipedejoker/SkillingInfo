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

This has been verified end-to-end: `./gradlew build` compiles cleanly, and
`SkillingInfoPlugin` starts up, registers its `GameTick`/`StatChanged`/
`GameStateChanged` subscribers, and reaches `Plugin SkillingInfoPlugin is
now running` with zero exceptions. What hasn't been verified from this
environment is the sidebar rendering correctly and behaving right in a real
play session — that part's still on you.

## Compliance

Skilling Info only observes RuneLite events (`StatChanged`, `GameTick`,
inventory/ground-item/bank container changes). It never sends input, clicks,
or menu actions, and makes no network requests — everything is local. See
`SPEC.md` §47.
