# Skilling Info — UI design brief

A brief for designing the sidebar layouts for a RuneLite plugin. No prior
knowledge of the project assumed; everything needed is below.

---

## 1. What the plugin does

**Skilling Info** records what a player actually did during a session of
Old School RuneScape, and — the part nothing else does — **what their
account actually gained from it**.

Existing tools answer "how much XP did I get?" or "what dropped?".
Skilling Info answers:

> After playing this activity this way for this long, what did I actually
> keep?

It follows every item through its full lifecycle:

```
generated → picked up → dropped → picked up again → banked
```

So if you chop 618 teak logs, drop 112 to save time, pick 4 back up and
bank 510, it reports **510 net retained, 82.5% retention** — not "618 logs
chopped". That gap between gross output and real account gain is the whole
point of the product, and the UI should make it feel like the point.

Its users are efficiency-minded players, disproportionately Ironman
accounts (a mode where every resource is self-gathered, so retention
genuinely matters).

---

## 2. Hard constraints — please read before sketching

These are not preferences. They're imposed by RuneLite and can't be
negotiated.

| Constraint | Detail |
|---|---|
| **Width is fixed at 213px** | The sidebar panel is 225px wide with 6px padding each side. It **cannot** be resized, and there is no horizontal scrolling. This is the single biggest design constraint. |
| Height scrolls vertically | Unlimited vertical space, but the user has to scroll for it, so above-the-fold priority matters. |
| Two themes | Must work in both RuneLite's dark and light themes. Colours come from a fixed system palette (greys, plus one brand orange accent). Don't specify arbitrary brand colours. |
| Fonts are fixed | Only RuneLite's own UI fonts are available: a small regular and a small bold, both pixel-style at roughly 11–12px. No custom typefaces, no large display type, no font weights beyond regular/bold. |
| It's Java Swing, not web | No gradients-on-everything, no drop shadows, no blur, no animation, no rounded corners on arbitrary elements, no SVG icons. Think "native desktop panel from 2010", not "modern web app". Flat fills, 1px borders, plain rectangles. |
| Icons come from the game | Item icons (~32×32 game sprites) and skill icons (~20×20) can be pulled directly from the game cache. We cannot commission or draw new iconography for items — it's whatever OSRS already has. |
| No in-game overlay | v0.1 is sidebar-only. Don't design a HUD. |

**Practical implication:** a two-column label/value row at this width gives
roughly 20 characters for the label and 8 for the value. Item names like
"Adamantite bar" are already 14 characters before any number. Long content
must wrap, truncate, or be hidden behind interaction — it cannot spill
sideways.

---

## 3. The screens

There are six states. All six need a layout.

### 3.1 Idle
Nothing detected yet. Currently a centred paragraph of grey text. Should
explain, without nagging, that the plugin is watching and will offer to
start a session when it recognises repeated activity.

### 3.2 Detection prompt
The plugin has noticed repeated activity and asks whether to track it.
Two actions: **Start** and **Ignore**. Auto-dismisses after ~15s.

```
Fishing activity detected
5 XP drops · 38 seconds · +875 XP
[ Start ]  [ Ignore ]
```

This is the plugin's only interruption. It should read as a quiet offer,
not an alert.

### 3.3 Active session — the main screen

This is where most of the time is spent and where the clutter problem is
worst. Real data from a live session:

```
Skill            Woodcutting          ← plus skill icon
Activity         Oak trees

Total            01:42:18
Active           01:31:04
Idle             00:11:14
XP               +71,420
Active XP/hr     47,075
Overall XP/hr    41,892
Logs             842
Logs/hr          556
Actions          842
Actions/hr       556
Retention        97.0%

OUTPUT
Oak logs         +819  (510 banked)
Bird nest        +3

FUTURE XP
Firemaking       +155.6k

[ Pause ]  [ Stop Session ]
```

Fifteen-plus rows of near-identical grey text. **Not all of it is equally
important** — the hierarchy question is the core of this brief. Some
guidance on relative value:

- **Retention** and **net retained per item** are the differentiator. If a
  player takes one glance, this is what should land.
- Time/XP/rates are table stakes — other plugins show these. Useful, not
  distinctive.
- "Actions" and "Logs" are near-duplicates in most sessions (both count
  actions); they diverge only in specific cases.
- Future XP is a projection, and must never be mistaken for XP already
  earned.

Some items have a **dropdown** attached (choosing what a resource will
eventually be used for — e.g. oak logs → burn / fletch / plank — which
changes the Future XP figure). Only items with a genuine choice get one.

### 3.4 Paused session
Same as active, visually distinguished. Currently only the button label
changes from "Pause" to "Resume", which is too subtle to notice.

### 3.5 History list
Sessions are filtered by skill via a row of clickable skill icons at the
top of the panel (this navigation pattern works well and should be kept).
Each row currently shows:

```
Oak trees
Aug 11, 17:24
41m active · 91,325 XP/hr · +62,710 XP
Oak logs  +819  (510 banked)
Bird nest  +3
```

With a varied session this becomes a very long wall. **Rows should collapse
to a summary by default and expand for detail.**

### 3.6 Session detail (expanded)
The expanded state is where the full lifecycle breakdown finally has room
to live — currently it's tracked but never shown anywhere:

```
Oak logs
Generated        618
Dropped          112
Picked up again    4
Consumed           0
Banked           510
Net retained     510
```

---

## 4. What we want from this pass, in priority order

1. **Item icons.** The panel is currently pure text. Every comparable
   plugin (Loot Tracker, Banked Experience) shows item sprites. This is
   the single biggest reason it reads as unfinished.
2. **Visual hierarchy.** Section structure, dividers, and deliberate use of
   the limited type and colour range so the panel reads as organised
   rather than as one undifferentiated grey block. Make the retention /
   net-retained story prominent.
3. **Collapse & expand, plus a per-skill summary.** Long lists need to
   collapse. Each skill's history should open with an aggregate (total
   time, total XP, total banked) before the individual sessions.

Explicitly **not** in scope: a new plugin logo/icon, and any in-game
overlay.

---

## 5. Reference points

Worth looking at these RuneLite plugins for the established visual
language — they're the bar we're being measured against:

- **Loot Tracker** (built into RuneLite) — item sprites in a grid, collapsible
  per-kill sections. Closest thing to our item-flow display.
- **Banked Experience** (plugin hub) — per-item rows with a dropdown
  selector, which is exactly our Future XP interaction.
- **XP Tracker** (built in) — the standard for compact skill rows in this
  width.

The goal is to look like it belongs in RuneLite, not to stand out from it.

---

## 6. Deliverables

- Layouts for all six states in §3, at exactly **213px content width**.
- Both dark and light theme treatments (or a clear statement of how the
  palette maps between them).
- Collapsed *and* expanded treatments for the history row and item flow.
- A note on what to do when content overflows: which item names truncate,
  which values abbreviate (`155.6k` vs `155,600`), what wraps.

Annotated static mockups are fine and expected. No prototype needed.

---

## 7. Open questions worth a designer's opinion

- Is a two-column label/value list even right at this width, or should
  stats be grouped into denser tiles?
- Should "Actions/Actions per hour" be shown at all when it duplicates the
  item count, or only when the two genuinely differ?
- How should a *projection* (Future XP) be visually separated from
  *facts* (XP earned, items banked) so it's never misread as the latter?
- The prompt in §3.2 is the plugin's only interruption — how prominent
  should it be without becoming annoying?
