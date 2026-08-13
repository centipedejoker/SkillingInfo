package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import java.util.function.IntUnaryOperator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/**
 * Orchestrates the session state machine (SPEC.md §7 [v2]) with multi-skill
 * tracking-group support (SPEC.md §7a).
 * <p>
 * Phase 1 simplification: only one tracking group is watched/tracked at a
 * time (candidate, prompted, active or paused), matching how a player
 * actually plays - one activity at a time. While a session is ACTIVE/PAUSED
 * for one group, XP outside that group is not fed into candidate detection
 * at all. Concurrent multi-group detection can be revisited later if it
 * proves useful in practice - this only generalises "skill" to "tracking
 * group" within a single in-flight slot, it doesn't track several groups
 * at once.
 */
@Slf4j
public class SessionManager
{
	private final SkillingInfoConfig config;
	private final SessionRepository repository;
	private final ItemUseStore itemUseStore;
	private final XpTracker xpTracker = new XpTracker();
	private final SessionClock clock = new SessionClock();
	private final InventoryDeltaTracker inventoryDeltaTracker = new InventoryDeltaTracker();
	// Equipment is diffed with the same engine: wielding an item removes it
	// from the inventory, which would otherwise look exactly like consuming
	// it (§18 [v7]).
	private final InventoryDeltaTracker equipmentDeltaTracker = new InventoryDeltaTracker();
	/**
	 * §18 `[v9]`: the other containers an item can move into without leaving
	 * the account. Each is diffed by the same engine as the inventory and
	 * claimed the same way, so stowing something is never mistaken for using
	 * it up.
	 * <p>
	 * Keyed by {@code gameval.InventoryID}. The coal bag, herb sack and gem
	 * sack are deliberately absent - they have no client-side container at
	 * all, so nothing here can see them and §50's unexplained-loss bucket is
	 * the backstop instead.
	 */
	private final Map<Integer, InventoryDeltaTracker> sideContainers = new HashMap<>();

	private final DropCorrelator dropCorrelator = new DropCorrelator();
	private final GroundItemTracker groundItemTracker = new GroundItemTracker();
	private final PickupCorrelator pickupCorrelator = new PickupCorrelator();
	private final BankCorrelator bankCorrelator = new BankCorrelator();

	private final Map<Skill, CandidateBuffer> buffers = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> pendingTickDeltas = new EnumMap<>(Skill.class);

	@Getter
	private final List<ActivitySession> history = new ArrayList<>();

	@Getter
	private SessionState state = SessionState.IDLE;

	@Getter
	private ActivitySession currentSession;

	@Getter
	private PromptSummary pendingPrompt;

	/**
	 * `[v8]` Whether PAUSED was entered by the player rather than by the idle
	 * threshold (§13a). Deliberately a property of how the state was entered
	 * rather than a state of its own: a manual pause looks and behaves exactly
	 * like an auto-pause - clock idle, panel dimmed - and differs only in what
	 * is allowed to end it. Splitting the enum would have forced every
	 * {@code state == PAUSED} check in the manager and the UI to be revisited
	 * for the sake of one branch.
	 * <p>
	 * While it is set the session records nothing at all - not XP, items or
	 * kills. That follows from the clock stopping: crediting XP against a
	 * frozen active time makes every rate in §14 climb for as long as the
	 * pause lasts, which is precisely the silently-wrong headline figure the
	 * plugin exists to avoid. A player who pauses and forgets loses the
	 * record of that work, but loses it visibly - the panel carries a
	 * full-width PAUSED band the whole time.
	 */
	@Getter
	private boolean manuallyPaused;

	/** Group key (SPEC.md §7a) of whatever candidate/prompt is in flight. */
	private Skill candidateGroupKey;
	private Skill suppressedGroupKey;
	private int suppressedUntilTick;
	private int promptExpiresAtTick;
	private int lastQualifyingTick;
	private int currentTick;

	// SPEC.md §16 [v7 fix]: RuneLite doesn't guarantee the inventory update
	// for a skilling action lands in the exact same tick as its XP - an
	// exact-tick check silently attributed nothing, ever. Track the last
	// tick XP was credited and accept a small trailing window instead.
	private static final int GENERATION_WINDOW_TICKS = 2;
	private int lastXpCreditTick = Integer.MIN_VALUE / 2;

	// SPEC.md §6/§10 "buffered output events where safe" [v7]: items
	// generated during the candidate detection window (before Start is
	// pressed) are buffered the same way XP already is, so a session
	// doesn't undercount relative to the XP total it started with. Only
	// generation is backfilled this way - drops during the pre-session
	// window are a rarer, lower-value case and stay out of scope.
	private final Map<Integer, Integer> candidateGeneratedBuffer = new HashMap<>();
	private int lastCandidateXpTick = Integer.MIN_VALUE / 2;

	// §37 [v7]: the Slayer plugin reports progress against the *task*, which
	// may have been part-finished before this session began. Baselining the
	// remaining count at session start is what keeps kills session-scoped -
	// the same trap that made XP Tracker's rates unusable (§14).
	private int lastRemainingAmount = -1;

	// §18 [v9]: how long after a death the catch-alls stay suppressed. The
	// inventory doesn't clear in the same tick the death animation starts.
	private static final int DEATH_SUPPRESSION_TICKS = 5;
	private int deathSuppressedUntilTick = Integer.MIN_VALUE / 2;

	/**
	 * Whether RuneLite's Slayer plugin is actually reporting a task. It is a
	 * declared dependency, but that only guarantees it is loaded and
	 * injectable - not that the user has it switched on. With it off, its
	 * getters simply return null and 0, so kills silently sit at zero with
	 * nothing to explain why. The panel uses this to say so.
	 */
	@Getter
	private boolean slayerTaskVisible;

	/**
	 * `[v9]` Whether RuneLite's Loot Tracker is actually running.
	 * <p>
	 * The same trap as {@link #slayerTaskVisible}, and worse for being
	 * invisible: `LootReceived` is posted by the Loot Tracker *plugin*, not by
	 * the client, so with it switched off a combat session records
	 * `generated = 0` and the retention block - the plugin's headline claim
	 * for combat - hides itself entirely rather than reading zero. A comment
	 * in the source asserted the opposite, that the event came from core.
	 */
	@Getter
	private boolean lootTrackingVisible;

	public void setLootTrackingVisible(boolean visible)
	{
		this.lootTrackingVisible = visible;
	}

	/**
	 * `[v9]` itemId → the id this one is a *note of*, or -1 when it isn't a
	 * note. Supplied as a function rather than resolved here because the
	 * answer comes from {@code ItemManager}, which asserts the client thread -
	 * and {@code session/} deliberately depends on neither the client nor the
	 * UI. The plugin memoises it; every call site here is already on the
	 * client thread.
	 */
	private final IntUnaryOperator unnotedId;

	public SessionManager(SkillingInfoConfig config, SessionRepository repository,
		ItemUseStore itemUseStore, IntUnaryOperator unnotedId)
	{
		this.config = config;
		this.repository = repository;
		this.itemUseStore = itemUseStore;
		this.unnotedId = unnotedId;
	}

	/**
	 * `[v9]` Installs freshly loaded history.
	 * <p>
	 * Takes the list rather than reading it, because the read is disk I/O and
	 * must not happen on the client thread - at ten thousand sessions
	 * {@code loadAll} is a ~480ms parse, and it used to run on every region
	 * change (§44 `[v9]`).
	 */
	public void setHistory(List<ActivitySession> loaded)
	{
		history.clear();
		history.addAll(loaded);
	}

	/**
	 * How much of the Start/Ignore prompt's timeout is left, 1.0 → 0.0.
	 * Drives the draining bar in the prompt (design §3.2) - the only
	 * moving element in the panel, and the only cue that the offer expires
	 * rather than waiting indefinitely.
	 */
	public double getPromptRemainingFraction()
	{
		if (state != SessionState.PROMPTED)
		{
			return 0;
		}
		int window = CandidateDetector.secondsToTicks(config.promptTimeoutSeconds());
		if (window <= 0)
		{
			return 0;
		}
		return Math.max(0, Math.min(1, (promptExpiresAtTick - currentTick) / (double) window));
	}

	public SessionClock getClock()
	{
		return clock;
	}

	// ------------------------------------------------------------------
	// Event ingestion
	// ------------------------------------------------------------------

	public void onStatChanged(Skill skill, int totalXp)
	{
		int delta = xpTracker.pollDelta(skill, totalXp);
		if (delta <= 0)
		{
			return;
		}
		pendingTickDeltas.merge(skill, delta, Integer::sum);
	}

	/**
	 * SPEC.md §48: feeds the current inventory snapshot into the delta
	 * engine every time it changes. Diffing happens immediately; the
	 * resulting deltas are picked up on the next {@link #onGameTick}.
	 */
	public void onInventoryChanged(Item[] items)
	{
		inventoryDeltaTracker.onInventoryChanged(items);
	}

	/**
	 * SPEC.md §25a step 2: feeds the bank container into the correlator on
	 * every change while the bank is open, not just at close - per-tick
	 * diffing is what makes "Deposit All", partial deposits and
	 * withdraw-then-redeposit sequences within one visit resolve correctly.
	 */
	public void onBankChanged(Item[] items)
	{
		bankCorrelator.onBankChanged(items);

		// §39: a bank visit is the natural trip boundary. Recorded from
		// Phase 1 onwards as a plain timestamp list; aggregating trips out
		// of it is still deferred, but the data is no longer lost.
		if (isRecording() && currentSession != null)
		{
			currentSession.recordBankVisit(Instant.now());
		}
	}

	/**
	 * §37: Slayer task progress, polled each tick. Kills are derived from
	 * the *change* in remaining count since the last observation, never from
	 * the task's own totals, so a session that joins a part-finished task
	 * counts only what happened while it was running.
	 * <p>
	 * A rise in the remaining count means a new task was assigned, so the
	 * baseline resets rather than recording a negative.
	 * <p>
	 * `[v8]` Counts while PAUSED as well as ACTIVE, like every other piece of
	 * activity evidence (§13 [v4]), and resumes the session the same way loot,
	 * pickups, drops and banking already do - a kill is the strongest possible
	 * signal that a combat session is still in progress. Previously this was
	 * the one signal that discarded its evidence <em>and</em> advanced the
	 * baseline past it, so a kill during a pause was lost outright rather than
	 * deferred, while loot from that very same kill was credited and did
	 * resume the session.
	 */
	public void onSlayerTaskUpdate(String task, String location, int remainingAmount)
	{
		slayerTaskVisible = task != null && !task.isEmpty();

		if ((state != SessionState.ACTIVE && state != SessionState.PAUSED) || currentSession == null
			|| !TrackingGroups.isCombatGroup(currentSession.getSkill()) || manuallyPaused)
		{
			// Advancing the baseline past kills we're declining to record is
			// exactly what `[v8]` above calls out as a trap - but here it is
			// the point, not an accident: the player asked for this stretch
			// not to count, so those kills must not arrive in a lump on
			// resume. The distinction is instruction versus inference.
			lastRemainingAmount = remainingAmount;
			return;
		}

		if (lastRemainingAmount >= 0 && remainingAmount < lastRemainingAmount)
		{
			currentSession.recordKills(lastRemainingAmount - remainingAmount);
			recordNonXpActivity();
		}
		lastRemainingAmount = remainingAmount;

		// §16: for combat the task is a far better activity name than
		// anything the item output could suggest
		if (task != null && !task.isEmpty())
		{
			currentSession.setActivity(location == null || location.isEmpty()
				? task
				: task + ", " + location);
		}
	}

	/**
	 * §37/§18: what a monster dropped, from RuneLite's own loot tracking
	 * rather than re-derived from raw events (§5).
	 * <p>
	 * Recorded as generated-only: it is on the floor, not in the inventory.
	 * It becomes acquired only if {@link PickupCorrelator} later confirms it
	 * was taken - which is precisely the gap between gross loot and account
	 * gain this plugin exists to measure.
	 */
	public void onNpcLootReceived(Map<Integer, Integer> items)
	{
		if ((state != SessionState.ACTIVE && state != SessionState.PAUSED)
			|| currentSession == null || manuallyPaused)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			currentSession.addGeneratedOnly(entry.getKey(), entry.getValue());
		}
		recordNonXpActivity();
	}

	/**
	 * §18 `[v9]`: the local player died, so the inventory is about to empty
	 * for a reason that has nothing to do with the activity. Suppresses the
	 * catch-alls for a few ticks - the inventory clears a tick or two after
	 * the death animation begins, not in the same one.
	 */
	public void onLocalPlayerDeath()
	{
		log.debug("Local player died at tick {}", currentTick);
		deathSuppressedUntilTick = currentTick + DEATH_SUPPRESSION_TICKS;
	}

	/** SPEC.md §18 [v7]: needed only to tell a wield apart from a consumption. */
	public void onEquipmentChanged(Item[] items)
	{
		equipmentDeltaTracker.onInventoryChanged(items);
	}

	/**
	 * SPEC.md §18 `[v9]`: any other container the player can move items into
	 * without losing them - looting bag, seed box, seed vault, group storage.
	 * Diffed exactly like the worn container and claimed the same way.
	 */
	public void onSideContainerChanged(int containerId, Item[] items)
	{
		sideContainers.computeIfAbsent(containerId, id -> new InventoryDeltaTracker(true))
			.onInventoryChanged(items);
	}

	/**
	 * SPEC.md §21: records a Drop menu click as a pending correlation.
	 * No-op unless a session is actually running - a click with nothing to
	 * attribute it to isn't useful data.
	 */
	public void onDropClicked(int itemId)
	{
		if (isRecording())
		{
			dropCorrelator.onDropClicked(itemId, currentTick);
		}
	}

	/**
	 * SPEC.md §20a: records a ground-item "Take" click as a pending
	 * correlation. No-op unless a session is actually running.
	 */
	public void onTakeClicked(int itemId)
	{
		if (isRecording())
		{
			log.debug("Take clicked: itemId={} tick={}", itemId, currentTick);
			pickupCorrelator.onTakeClicked(itemId, currentTick);
		}
	}

	/**
	 * Whether a session exists and is currently taking evidence: ACTIVE, or
	 * auto-paused and therefore still able to resume on the next thing that
	 * happens. False for a manual pause (§13a `[v8]`), which records nothing
	 * until the player resumes.
	 */
	private boolean isRecording()
	{
		return (state == SessionState.ACTIVE || state == SessionState.PAUSED) && !manuallyPaused;
	}

	/** SPEC.md §48: feeds live ground-item state into {@link GroundItemTracker}. */
	public void onGroundItemSpawned(WorldPoint point, TileItem item)
	{
		groundItemTracker.onItemSpawned(point, item);
	}

	public void onGroundItemQuantityChanged(WorldPoint point, TileItem item, int oldQuantity, int newQuantity)
	{
		groundItemTracker.onItemQuantityChanged(point, item, oldQuantity, newQuantity, currentTick);
	}

	public void onGroundItemDespawned(WorldPoint point, TileItem item)
	{
		groundItemTracker.onItemDespawned(point, item, currentTick);
	}

	/**
	 * §48 `[v9]`: the scene went away, so every tile the tracker holds is
	 * stale. RuneLite core clears its own ground-item state the same way
	 * rather than relying on despawn events, which are not guaranteed for an
	 * unloaded region.
	 */
	public void onWorldViewUnloaded()
	{
		groundItemTracker.clear();
	}

	/**
	 * Item-flow correlators (Phase 2+: pickup/drop/bank) call this so a long
	 * loot-banking trip with no XP in the idle window doesn't incorrectly
	 * auto-pause a session that's still actively in progress (SPEC.md §13
	 * [v4]). No-op outside an active session - item-flow events only exist
	 * in relation to whatever session is already running.
	 */
	public void recordNonXpActivity()
	{
		// `[v8]` The guard lives here, at the single choke point every
		// auto-resume passes through, as well as at each caller - so a future
		// caller can't reintroduce the resume by forgetting about it.
		if (manuallyPaused)
		{
			return;
		}

		if (state == SessionState.ACTIVE)
		{
			lastQualifyingTick = currentTick;
		}
		else if (state == SessionState.PAUSED)
		{
			state = SessionState.ACTIVE;
			lastQualifyingTick = currentTick;
		}
	}

	public void onGameTick(int tick)
	{
		this.currentTick = tick;

		// SPEC.md §9 [v4]: more than one *group key* (§7a) changing in the
		// same tick is the reward-burst signal, not more than one raw skill
		// - Attack+Hitpoints+Slayer collapse to one group key (SLAYER) and
		// are normal combat, while Mining+Smithing+Crafting stay three
		// distinct group keys and are still correctly rejected.
		Set<Skill> groupKeysThisTick = EnumSet.noneOf(Skill.class);
		for (Skill skill : pendingTickDeltas.keySet())
		{
			groupKeysThisTick.add(TrackingGroups.groupKey(skill));
		}
		// SPEC.md §9 [v7]: some equipment awards a second skill incidentally
		// on a single action - infernal tools (Woodcutting+Firemaking etc),
		// bonecrusher, herbicide. Those aren't reward bursts, but they do
		// look exactly like one, which previously made it impossible to
		// start a session at all while using them.
		Skill primaryGroupKey = TrackingGroups.resolvePrimary(groupKeysThisTick);
		boolean rewardBurstTick = groupKeysThisTick.size() > 1 && primaryGroupKey == null;

		for (Map.Entry<Skill, Integer> entry : pendingTickDeltas.entrySet())
		{
			Skill groupKey = TrackingGroups.groupKey(entry.getKey());

			// A byproduct's XP is still real and still credited to the
			// session, but it must never drive detection or start a session
			// of its own - an infernal axe shouldn't offer you a Firemaking
			// session while you're chopping.
			boolean isIncidentalByproduct = primaryGroupKey != null && groupKey != primaryGroupKey;

			if (rewardBurstTick || isIncidentalByproduct)
			{
				creditActiveSessionOnly(entry.getKey(), entry.getValue());
			}
			else
			{
				processQualifyingEvent(entry.getKey(), entry.getValue());
			}
		}
		pendingTickDeltas.clear();

		creditItemFlow();

		switch (state)
		{
			case CANDIDATE:
				tickCandidate();
				break;
			case PROMPTED:
				tickPrompted();
				break;
			case ACTIVE:
				clock.tickActive();
				if (currentSession != null)
				{
					// rates derive from activeSeconds, so it has to be live
					// rather than only written at finalisation
					currentSession.setActiveSeconds(clock.getActiveSeconds());
				}
				tickActive();
				break;
			case PAUSED:
				clock.tickIdle();
				break;
			case SUPPRESSED:
				tickSuppressed();
				break;
			default:
				break;
		}
	}

	/**
	 * `[v9]` The client is now looking at a different account, which may
	 * happen without ever passing through the login screen - a reconnect or a
	 * hop can do it. Everything baselined against the previous account has to
	 * go, or the difference between the two is read as though the player had
	 * earned it.
	 */
	public void onAccountChanged()
	{
		onLogout();
	}

	public void onLogout()
	{
		if (state == SessionState.ACTIVE || state == SessionState.PAUSED)
		{
			stop();
		}

		// `[v9]` XpTracker holds each skill's last-seen *total*, so a stale
		// baseline doesn't decay - it turns the next account's first sync
		// into a single enormous "gain". Logging into a 10m Woodcutting main
		// after a 500k alt offered a session of +9,500,200 XP and would have
		// written it to an append-only history file. reset() existed for this
		// and had no callers anywhere.
		xpTracker.reset();
		inventoryDeltaTracker.resetForNewAccount();
		equipmentDeltaTracker.resetForNewAccount();
		sideContainers.values().forEach(InventoryDeltaTracker::resetForNewAccount);
		bankCorrelator.resetForNewAccount();
		lastRemainingAmount = -1;

		buffers.clear();
		pendingTickDeltas.clear();
		candidateGroupKey = null;
		candidateGeneratedBuffer.clear();
		manuallyPaused = false;
		state = SessionState.IDLE;
	}

	// ------------------------------------------------------------------
	// Internal transitions
	// ------------------------------------------------------------------

	private void creditActiveSessionOnly(Skill skill, int delta)
	{
		if ((state == SessionState.ACTIVE || (state == SessionState.PAUSED && !manuallyPaused))
			&& currentSession != null && currentSession.getSkill() == TrackingGroups.groupKey(skill))
		{
			currentSession.addXp(skill, delta);
			lastQualifyingTick = currentTick;
			lastXpCreditTick = currentTick;
		}
	}

	/**
	 * SPEC.md §16: correlates this tick's inventory deltas against the
	 * current session.
	 * <p>
	 * Both pools are drained by {@link #claim} in order of how specifically
	 * each signal explains a movement, and only what survives reaches the
	 * catch-all generation and consumption rules at the end. That ordering
	 * is the whole safeguard against counting one movement twice:
	 * <ol>
	 * <li>moves into or out of another container (worn, bank) - the item
	 * didn't enter or leave the account at all, it only travelled;</li>
	 * <li>click-gated correlations - confirmed pickups (§20a) and drops
	 * (§21), which matter because a Take click and a fresh qualifying XP
	 * event can land close together (looting right after a Slayer kill);</li>
	 * <li>bank deposits (§25a), evidenced by the matching bank increase;</li>
	 * <li>whatever is left, and only within a small window of ticks after XP
	 * was last credited (§16 [v7 fix]): an increase is the activity's output
	 * (§16), a decrease is the activity consuming something (§18).</li>
	 * </ol>
	 * Step 1 is the one the catch-alls are most exposed to, because they
	 * accept <em>anything</em> unexplained inside the XP window: without it,
	 * taking off a cape mid-chop books an axe as woodcutting output, and
	 * withdrawing 27 raw fish at a bank books them as having been caught.
	 */
	private void creditItemFlow()
	{
		Map<Integer, Integer> increased = inventoryDeltaTracker.consumeIncreased();
		Map<Integer, Integer> decreased = inventoryDeltaTracker.consumeDecreased();

		// §16/§18: an inventory change with a matching move in another
		// container isn't a gain or a loss at all - the item only travelled.
		// Both directions of both containers are drained every tick,
		// whatever the session state: a pool emptied on only one code path
		// accumulates stale deltas that later cancel a real, unrelated
		// movement of the same item.
		Map<Integer, Integer> equipped = equipmentDeltaTracker.consumeIncreased();
		Map<Integer, Integer> unequipped = equipmentDeltaTracker.consumeDecreased();
		Map<Integer, Integer> withdrawn = bankCorrelator.consumeDecreased();

		// Claimed ahead of every other signal, including the click-gated
		// ones: a second container moving the item is stronger evidence than
		// a menu click, which only says what the player asked for.
		claim(increased, unequipped);
		claimWithNotes(increased, withdrawn);
		claim(decreased, equipped);

		// §18 [v9]: same treatment for every other container an item can sit
		// in without leaving the account. An item stowed in a looting bag or
		// a seed box has not been used up.
		for (InventoryDeltaTracker container : sideContainers.values())
		{
			claim(decreased, container.consumeIncreased());
			claim(increased, container.consumeDecreased());
		}

		if (!increased.isEmpty() || !decreased.isEmpty())
		{
			log.debug("Inventory delta at tick {}: increased={} decreased={} (lastXpCreditTick={}, state={})",
				currentTick, increased, decreased, lastXpCreditTick, state);
		}

		// `[v9]` PROMPTED buffers too - the player is still producing while
		// the offer is on screen, and start() replays both buffers
		if ((state == SessionState.CANDIDATE || state == SessionState.PROMPTED)
			&& currentTick - lastCandidateXpTick <= GENERATION_WINDOW_TICKS)
		{
			for (Map.Entry<Integer, Integer> entry : increased.entrySet())
			{
				candidateGeneratedBuffer.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		if ((state != SessionState.ACTIVE && state != SessionState.PAUSED)
			|| currentSession == null || manuallyPaused)
		{
			// Every pool feeding the catch-alls has already been drained
			// above; the bank's is the one exception, because resolve() owns
			// it. Discard it here rather than let it carry - a deposit made
			// while nothing was being recorded must not be credited to the
			// next tick that is (§25a step 5, and §18 `[v8]`'s rule that a
			// pool emptied on only one path later cancels a real movement).
			bankCorrelator.discardPending();
			return;
		}

		Map<Integer, Integer> confirmedPickups = pickupCorrelator.resolve(currentTick, increased,
			(itemId, qty) -> groundItemTracker.claimDisappeared(itemId, currentTick, qty));
		claim(increased, confirmedPickups);
		for (Map.Entry<Integer, Integer> entry : confirmedPickups.entrySet())
		{
			creditPickup(entry.getKey(), entry.getValue());
		}
		if (!confirmedPickups.isEmpty())
		{
			// SPEC.md §13 [v4]: a pickup is deliberate activity, not idle time
			recordNonXpActivity();
		}

		// The "inventory rose while gaining XP" heuristic (§16) only makes
		// sense for gathering, where output lands directly in the inventory.
		// In combat XP is continuous, so it would mark *any* inventory gain
		// as generated - loot there comes from LootReceived instead.
		boolean gathering = !TrackingGroups.isCombatGroup(currentSession.getSkill());
		if (gathering && currentTick - lastXpCreditTick <= GENERATION_WINDOW_TICKS)
		{
			for (Map.Entry<Integer, Integer> entry : increased.entrySet())
			{
				log.debug("Crediting generated: itemId={} qty={}", entry.getKey(), entry.getValue());
				currentSession.addGenerated(entry.getKey(), entry.getValue());
			}
		}

		// drops are click-gated and therefore more specific than the bank
		// signature below - claimed out of the decrease pool first so one
		// inventory decrease can't read as both
		Map<Integer, Integer> confirmedDrops = dropCorrelator.resolve(currentTick, decreased);
		claim(decreased, confirmedDrops);
		for (Map.Entry<Integer, Integer> entry : confirmedDrops.entrySet())
		{
			currentSession.addDropped(entry.getKey(), entry.getValue());
		}
		if (!confirmedDrops.isEmpty())
		{
			// SPEC.md §13 [v4]: a drop is deliberate activity, not idle time
			recordNonXpActivity();
		}

		// the correlator reads the decrease pool without consuming it, so
		// claim it here - otherwise a deposit would also be counted as
		// consumption below. `[v9]`: everything the deposit *explains* is
		// claimed, which is more than the session may *credit* - depositing
		// stock you already had is capped to zero by §25a step 4, and used
		// to fall through to consumption as a result.
		BankCorrelator.Resolution banked = bankCorrelator.resolve(decreased,
			itemId -> currentSession.getOutstandingForBanking(itemId), unnotedId);
		claim(decreased, banked.getExplained());
		Map<Integer, Integer> confirmedBanked = banked.getCredited();
		for (Map.Entry<Integer, Integer> entry : confirmedBanked.entrySet())
		{
			log.debug("Crediting banked: itemId={} qty={}", entry.getKey(), entry.getValue());
			currentSession.addBanked(entry.getKey(), entry.getValue());
		}
		if (!confirmedBanked.isEmpty())
		{
			// SPEC.md §13 [v4]: banking is deliberate activity, not idle time -
			// this is the scenario that motivated broadening the idle signal
			// beyond XP in the first place
			recordNonXpActivity();
		}

		creditConsumption(decreased);

		// SPEC.md §16: reclassify from what's been produced so far. Cheap,
		// and re-running it every tick means the name sharpens as evidence
		// accumulates rather than being fixed by the first item seen.
		// Combat is named by its Slayer task instead (§37), which is both
		// more accurate and already set, so don't overwrite it.
		if (!TrackingGroups.isCombatGroup(currentSession.getSkill()))
		{
			currentSession.setActivity(ActivityClassifier.classify(currentSession));
		}
	}

	/**
	 * Removes {@code claimed} from {@code pool}, dropping entries once
	 * they're fully accounted for.
	 * <p>
	 * This is the one mechanism that keeps a single inventory movement from
	 * being counted twice: every signal that can explain part of a delta
	 * takes its share out of the pool, most specific first, and only what
	 * survives reaches the catch-all generation and consumption rules.
	 * Entries are removed rather than left at zero so the catch-alls can
	 * iterate the pool without re-checking for spent ones.
	 */
	private static void claim(Map<Integer, Integer> pool, Map<Integer, Integer> claimed)
	{
		for (Map.Entry<Integer, Integer> entry : claimed.entrySet())
		{
			claimOne(pool, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Claims {@code qty} of one item out of {@code pool}.
	 *
	 * @return how much of {@code qty} the pool could not account for
	 */
	private static int claimOne(Map<Integer, Integer> pool, int itemId, int qty)
	{
		Integer available = pool.get(itemId);
		if (available == null)
		{
			return qty;
		}
		int left = available - qty;
		if (left > 0)
		{
			pool.put(itemId, left);
			return 0;
		}
		pool.remove(itemId);
		return -left;
	}

	/**
	 * `[v9]` {@link #claim}, but tolerant of the noted/unnoted split.
	 * <p>
	 * A bank stores items unnoted. Withdrawing "as note" puts a *different*
	 * item id in the inventory, so the two halves of one movement don't share
	 * a key and a plain claim can't pair them - the withdrawal went unclaimed
	 * and §16's catch-all booked the arriving notes as skilling output. That
	 * is §18 `[v8]`'s bug surviving on a path its fix didn't reach.
	 * <p>
	 * Only the note→item direction is ever asked for, because it is the only
	 * one RuneLite itself trusts: {@code ItemManager.canonicalize} reads
	 * {@code getLinkedNoteId()} exclusively behind a {@code getNote() != -1}
	 * guard. So rather than ask what the noted form of a bank id is, this
	 * looks through the pool for a key that is a note *of* the claimed id.
	 * The pool holds a handful of ids per tick, so the scan is free.
	 */
	private void claimWithNotes(Map<Integer, Integer> pool, Map<Integer, Integer> claimed)
	{
		for (Map.Entry<Integer, Integer> entry : claimed.entrySet())
		{
			int unaccounted = claimOne(pool, entry.getKey(), entry.getValue());
			if (unaccounted <= 0)
			{
				continue;
			}

			Integer notedForm = null;
			for (Integer key : pool.keySet())
			{
				if (key != entry.getKey() && unnotedId.applyAsInt(key) == entry.getKey())
				{
					notedForm = key;
					break;
				}
			}
			if (notedForm != null)
			{
				claimOne(pool, notedForm, unaccounted);
			}
		}
	}

	/**
	 * SPEC.md §18: an inventory decrease that no more specific signal
	 * explains, arriving while the session is gaining XP, is the activity
	 * consuming something - the raw fish behind the cooked one, the ore
	 * behind the bar, the runes behind the cast.
	 * <p>
	 * This is deliberately the exact mirror of how generation is detected
	 * (§16): same XP window, opposite direction. Everything with a more
	 * specific explanation has already claimed its share of the decrease
	 * pool first - drops (click-gated), bank deposits (bank-backed), and
	 * wields (matched by an equipment increase) - so what remains is
	 * genuinely unexplained loss during productive activity.
	 * <p>
	 * Note this makes §18's ITEM_TRANSFORMED recipe table unnecessary for
	 * correct accounting: cooking 100 raw sharks records 100 consumed and
	 * 100 generated independently, which nets out correctly without
	 * needing to know that one specifically became the other.
	 * <p>
	 * Known limitation: moving items into another container that isn't the
	 * bank - a looting bag, a POH storage - reads as consumption. That
	 * undercounts retention rather than inventing gain, which is the side
	 * §27 says to err on.
	 */
	/**
	 * `[v9]` Whether a tick's unexplained decrease is too big to be an
	 * activity using something up.
	 * <p>
	 * The motivating case is dying. Combat refreshes {@code lastXpCreditTick}
	 * on every hit, so the consumption window is permanently open during a
	 * task - and the generation catch-all is correctly disabled for combat
	 * while this one was not. Die carrying 500k coins and the ledger read
	 * `Consumed -500,000`, permanently: gravestone retrieval is never
	 * re-credited, because there is no Take click and generation is off.
	 * <p>
	 * Two signals, because neither covers the other. {@link
	 * #onLocalPlayerDeath()} is the accurate one and handles a death where
	 * items were kept. This is the backstop for a total loss with no death
	 * event: several distinct stacks gone at once and nothing left behind.
	 * Both conditions are needed - eating your last shark also empties an
	 * inventory, and that really is consumption.
	 */
	private boolean isBulkLoss(Map<Integer, Integer> decreased)
	{
		if (currentTick <= deathSuppressedUntilTick)
		{
			return true;
		}
		return decreased.size() >= 2 && inventoryDeltaTracker.isEmpty();
	}

	private void creditConsumption(Map<Integer, Integer> decreased)
	{
		if (currentTick - lastXpCreditTick > GENERATION_WINDOW_TICKS)
		{
			return;
		}

		// §18 [v9]: an activity with no inputs cannot have consumed anything,
		// so whatever left is an unexplained loss (§50) - a coal bag, a gem
		// sack, a deposit box. Recorded rather than discarded, but never
		// netted off: the coal is in the bag, not destroyed.
		boolean canConsume = !TrackingGroups.consumesNothing(currentSession.getSkill())
			&& !isBulkLoss(decreased);

		for (Map.Entry<Integer, Integer> entry : decreased.entrySet())
		{
			if (canConsume)
			{
				log.debug("Crediting consumed: itemId={} qty={}", entry.getKey(), entry.getValue());
				currentSession.addConsumed(entry.getKey(), entry.getValue());
			}
			else
			{
				log.debug("Crediting other loss: itemId={} qty={}", entry.getKey(), entry.getValue());
				currentSession.addOtherLoss(entry.getKey(), entry.getValue());
			}
		}
	}

	/**
	 * SPEC.md §22: a confirmed pickup first pays down however much of this
	 * item is still "dropped this session but not yet repicked" - that
	 * portion is a repickup correction, not a new net gain. Only the
	 * remainder (if any) is a genuinely new pickup.
	 */
	private void creditPickup(int itemId, int qty)
	{
		int outstanding = currentSession.getOutstandingDropped(itemId);
		int repickAmount = Math.min(qty, outstanding);
		int newAmount = qty - repickAmount;
		log.debug("Crediting pickup: itemId={} qty={} repicked={} new={}", itemId, qty, repickAmount, newAmount);

		if (repickAmount > 0)
		{
			currentSession.addRepicked(itemId, repickAmount);
		}
		if (newAmount > 0)
		{
			currentSession.addPickedUp(itemId, newAmount);
		}
	}

	/**
	 * One action per qualifying XP drop - but only where that means
	 * something.
	 * <p>
	 * For gathering, one drop is one log, ore or fish. For combat it is one
	 * <em>hit</em>, which is not a unit anyone counts, and worse: Attack,
	 * Strength, Hitpoints and Slayer XP all arrive in the same tick and all
	 * collapse to the same tracking group (§7a), so a single hit was
	 * recording three or four actions. Kills are the combat unit (§37), so
	 * combat sessions record no actions at all rather than a number that is
	 * both meaningless and inflated.
	 * <p>
	 * Byproducts (infernal tools, bonecrusher) route through
	 * {@link #creditActiveSessionOnly} instead, so one chop can't register
	 * as two actions either.
	 */
	private void recordActionIfMeaningful()
	{
		if (!TrackingGroups.isCombatGroup(currentSession.getSkill()))
		{
			currentSession.recordAction();
		}
	}

	private void processQualifyingEvent(Skill skill, int delta)
	{
		Skill groupKey = TrackingGroups.groupKey(skill);

		if (state == SessionState.ACTIVE && currentSession != null && currentSession.getSkill() == groupKey)
		{
			currentSession.addXp(skill, delta);
			recordActionIfMeaningful();
			lastQualifyingTick = currentTick;
			lastXpCreditTick = currentTick;
			return;
		}

		// [v2] resume threshold = 1 event, distinct from start threshold.
		// `[v8]` Auto-pause only: a manually paused session neither resumes
		// nor records, and falls through to the return below.
		if (state == SessionState.PAUSED && !manuallyPaused
			&& currentSession != null && currentSession.getSkill() == groupKey)
		{
			currentSession.addXp(skill, delta);
			recordActionIfMeaningful();
			lastQualifyingTick = currentTick;
			lastXpCreditTick = currentTick;
			state = SessionState.ACTIVE;
			return;
		}

		if (state == SessionState.ACTIVE || state == SessionState.PAUSED)
		{
			// a session for a different tracking group is already running
			return;
		}

		// `[v9]` A prompt for a *different* group blocks, as it always did.
		// One for this group does not: the player is still doing the thing
		// they are being asked about, and everything they do while deciding
		// used to be dropped on the floor - not buffered, not credited -
		// while start() went on to back-date startedAt across that window
		// anyway. Falling through to the buffer below is the whole fix.
		if (state == SessionState.PROMPTED && groupKey != candidateGroupKey)
		{
			return;
		}

		if (state == SessionState.SUPPRESSED)
		{
			if (groupKey == suppressedGroupKey && currentTick < suppressedUntilTick)
			{
				return;
			}
			// [v2] different group, or cooldown elapsed: fall through and
			// re-evaluate as a fresh candidate rather than staying stuck
			clearSuppression();
		}

		if (candidateGroupKey != null && candidateGroupKey != groupKey)
		{
			// already tracking a different candidate group; Phase 1 keeps
			// a single candidate slot
			return;
		}

		if (state != SessionState.CANDIDATE && state != SessionState.PROMPTED)
		{
			log.debug("Candidate opened: skill={} groupKey={} tick={}", skill, groupKey, currentTick);
		}
		candidateGroupKey = groupKey;
		if (state != SessionState.PROMPTED)
		{
			// `[v9]` a live prompt keeps collecting; it must not be reset
			// back to CANDIDATE underneath the player
			state = SessionState.CANDIDATE;
		}
		lastCandidateXpTick = currentTick;
		buffers.computeIfAbsent(groupKey, s -> new CandidateBuffer()).add(new QualifyingXpEvent(skill, delta, currentTick));
	}

	private void tickCandidate()
	{
		CandidateBuffer buffer = buffers.get(candidateGroupKey);
		if (buffer == null)
		{
			state = SessionState.IDLE;
			return;
		}

		int windowTicks = CandidateDetector.secondsToTicks(config.candidateWindowSeconds());
		buffer.pruneOlderThan(currentTick - windowTicks);

		if (buffer.isEmpty())
		{
			// [v2] buffer window expired without meeting the confidence
			// gate - discard and return to IDLE instead of hanging in
			// CANDIDATE forever
			buffers.remove(candidateGroupKey);
			candidateGroupKey = null;
			candidateGeneratedBuffer.clear();
			state = SessionState.IDLE;
			return;
		}

		// SPEC.md §1a/§7a [v4]: the combat group only reaches the gate once
		// real Slayer XP is present, not merely repeated Attack/Strength/
		// Hitpoints hits - this is what keeps plain, task-less bossing out
		// of scope without a separate "is a task active" check.
		if (TrackingGroups.isCombatGroup(candidateGroupKey)
			&& buffer.events().stream().noneMatch(e -> e.getSkill() == Skill.SLAYER))
		{
			return;
		}

		Optional<PromptSummary> summary = CandidateDetector.evaluate(candidateGroupKey, buffer, config);
		log.debug("Candidate tick: group={} buffered={} gateMet={}",
			candidateGroupKey, buffer.events().size(), summary.isPresent());
		if (summary.isPresent())
		{
			pendingPrompt = summary.get();
			promptExpiresAtTick = currentTick + CandidateDetector.secondsToTicks(config.promptTimeoutSeconds());
			state = SessionState.PROMPTED;
			log.debug("PROMPTED: {} ({} drops, +{} xp)",
				pendingPrompt.getSkill(), pendingPrompt.getDropCount(), pendingPrompt.getTotalXp());
		}
	}

	private void tickPrompted()
	{
		// `[v9]` keep the offer honest as the buffer behind it grows
		CandidateBuffer buffer = buffers.get(candidateGroupKey);
		if (buffer != null && !buffer.isEmpty())
		{
			pendingPrompt = CandidateDetector.summarise(candidateGroupKey, buffer);
		}

		if (currentTick >= promptExpiresAtTick)
		{
			// SPEC.md §10: expiry discards the buffer
			buffers.remove(candidateGroupKey);
			candidateGroupKey = null;
			candidateGeneratedBuffer.clear();
			pendingPrompt = null;
			state = SessionState.IDLE;
		}
	}

	private void tickActive()
	{
		int idleThresholdTicks = CandidateDetector.secondsToTicks(config.idleThresholdSeconds());
		if (currentTick - lastQualifyingTick >= idleThresholdTicks)
		{
			state = SessionState.PAUSED;
		}
	}

	private void tickSuppressed()
	{
		if (currentTick >= suppressedUntilTick)
		{
			clearSuppression();
			state = SessionState.IDLE;
		}
	}

	private void clearSuppression()
	{
		suppressedGroupKey = null;
		suppressedUntilTick = 0;
	}

	// ------------------------------------------------------------------
	// User-facing actions
	// ------------------------------------------------------------------

	public void start()
	{
		if (state != SessionState.PROMPTED || candidateGroupKey == null)
		{
			return;
		}

		CandidateBuffer buffer = buffers.get(candidateGroupKey);
		ActivitySession session = new ActivitySession();
		session.setSkill(candidateGroupKey);

		if (buffer != null && !buffer.isEmpty())
		{
			int firstTick = buffer.events().get(0).getTick();
			long elapsedMs = Math.max(0, (currentTick - firstTick)) * 600L;
			session.setStartedAt(Instant.now().minusMillis(elapsedMs));
			boolean countsActions = !TrackingGroups.isCombatGroup(candidateGroupKey);
			for (QualifyingXpEvent event : buffer.events())
			{
				session.addXp(event.getSkill(), event.getXpDelta());
				if (countsActions)
				{
					session.recordAction();
				}
			}
		}
		else
		{
			session.setStartedAt(Instant.now());
		}

		// SPEC.md §6/§10 [v7]: backfill items generated during the
		// candidate window too, same as XP above - otherwise net retained
		// silently undercounts relative to the XP total the session
		// already started with.
		for (Map.Entry<Integer, Integer> entry : candidateGeneratedBuffer.entrySet())
		{
			session.addGenerated(entry.getKey(), entry.getValue());
		}
		candidateGeneratedBuffer.clear();

		currentSession = session;
		clock.reset();
		inventoryDeltaTracker.reset();
		equipmentDeltaTracker.reset();
		sideContainers.values().forEach(InventoryDeltaTracker::reset);
		dropCorrelator.reset();
		pickupCorrelator.reset();
		bankCorrelator.reset();
		lastQualifyingTick = currentTick;
		lastXpCreditTick = currentTick;
		lastRemainingAmount = -1;
		manuallyPaused = false;

		buffers.remove(candidateGroupKey);
		candidateGroupKey = null;
		pendingPrompt = null;
		state = SessionState.ACTIVE;
	}

	public void ignore()
	{
		if (state != SessionState.PROMPTED || candidateGroupKey == null)
		{
			return;
		}

		suppressedGroupKey = candidateGroupKey;
		suppressedUntilTick = currentTick + CandidateDetector.secondsToTicks(config.suppressionCooldownSeconds());

		buffers.remove(candidateGroupKey);
		candidateGroupKey = null;
		candidateGeneratedBuffer.clear();
		pendingPrompt = null;
		state = SessionState.SUPPRESSED;
	}

	/**
	 * `[v8]` A manual pause is sticky: only {@link #resume} (or {@link #stop})
	 * ends it. Auto-pause is a guess about absence, so any evidence of
	 * activity rightly overrides it; a manual pause is an instruction, and
	 * nothing the player does afterwards is evidence against it.
	 */
	public void pause()
	{
		if (state == SessionState.ACTIVE)
		{
			state = SessionState.PAUSED;
			manuallyPaused = true;
		}
	}

	public void resume()
	{
		if (state == SessionState.PAUSED)
		{
			state = SessionState.ACTIVE;
			manuallyPaused = false;
			lastQualifyingTick = currentTick;
		}
	}

	public void stop()
	{
		if (currentSession == null)
		{
			return;
		}

		// §33: freeze the projection against the selection in force right
		// now. Changing a product later must affect future sessions only -
		// it must never rewrite what this one recorded.
		currentSession.setProjection(ProjectionBuilder.build(currentSession, itemUseStore));

		currentSession.setEndedAt(Instant.now());
		currentSession.setActiveSeconds(clock.getActiveSeconds());
		currentSession.setIdleSeconds(clock.getIdleSeconds());
		currentSession.setTotalSeconds(clock.getTotalSeconds());

		repository.append(currentSession);
		history.add(0, currentSession);

		currentSession = null;
		clock.reset();
		inventoryDeltaTracker.reset();
		equipmentDeltaTracker.reset();
		sideContainers.values().forEach(InventoryDeltaTracker::reset);
		dropCorrelator.reset();
		pickupCorrelator.reset();
		bankCorrelator.reset();
		groundItemTracker.clear();
		manuallyPaused = false;
		state = SessionState.IDLE;
	}
}
