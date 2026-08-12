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

	/**
	 * Whether RuneLite's Slayer plugin is actually reporting a task. It is a
	 * declared dependency, but that only guarantees it is loaded and
	 * injectable - not that the user has it switched on. With it off, its
	 * getters simply return null and 0, so kills silently sit at zero with
	 * nothing to explain why. The panel uses this to say so.
	 */
	@Getter
	private boolean slayerTaskVisible;

	public SessionManager(SkillingInfoConfig config, SessionRepository repository, ItemUseStore itemUseStore)
	{
		this.config = config;
		this.repository = repository;
		this.itemUseStore = itemUseStore;
	}

	public void init()
	{
		history.clear();
		history.addAll(repository.loadAll());
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
		if ((state == SessionState.ACTIVE || state == SessionState.PAUSED) && currentSession != null)
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
	 */
	public void onSlayerTaskUpdate(String task, String location, int remainingAmount)
	{
		slayerTaskVisible = task != null && !task.isEmpty();

		if (state != SessionState.ACTIVE || currentSession == null
			|| !TrackingGroups.isCombatGroup(currentSession.getSkill()))
		{
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
		if ((state != SessionState.ACTIVE && state != SessionState.PAUSED) || currentSession == null)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			currentSession.addGeneratedOnly(entry.getKey(), entry.getValue());
		}
		recordNonXpActivity();
	}

	/** SPEC.md §18 [v7]: needed only to tell a wield apart from a consumption. */
	public void onEquipmentChanged(Item[] items)
	{
		equipmentDeltaTracker.onInventoryChanged(items);
	}

	/**
	 * SPEC.md §21: records a Drop menu click as a pending correlation.
	 * No-op unless a session is actually running - a click with nothing to
	 * attribute it to isn't useful data.
	 */
	public void onDropClicked(int itemId)
	{
		if (state == SessionState.ACTIVE || state == SessionState.PAUSED)
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
		if (state == SessionState.ACTIVE || state == SessionState.PAUSED)
		{
			log.debug("Take clicked: itemId={} tick={}", itemId, currentTick);
			pickupCorrelator.onTakeClicked(itemId, currentTick);
		}
	}

	/** SPEC.md §48: feeds live ground-item state into {@link GroundItemTracker}. */
	public void onGroundItemSpawned(WorldPoint point, TileItem item)
	{
		groundItemTracker.onItemSpawned(point, item);
	}

	public void onGroundItemQuantityChanged(WorldPoint point, TileItem item)
	{
		groundItemTracker.onItemQuantityChanged(point, item);
	}

	public void onGroundItemDespawned(WorldPoint point, TileItem item)
	{
		groundItemTracker.onItemDespawned(point, item);
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

	public void onLogout()
	{
		if (state == SessionState.ACTIVE || state == SessionState.PAUSED)
		{
			stop();
		}
		buffers.clear();
		pendingTickDeltas.clear();
		candidateGroupKey = null;
		candidateGeneratedBuffer.clear();
		state = SessionState.IDLE;
	}

	// ------------------------------------------------------------------
	// Internal transitions
	// ------------------------------------------------------------------

	private void creditActiveSessionOnly(Skill skill, int delta)
	{
		if ((state == SessionState.ACTIVE || state == SessionState.PAUSED)
			&& currentSession != null && currentSession.getSkill() == TrackingGroups.groupKey(skill))
		{
			currentSession.addXp(skill, delta);
			lastQualifyingTick = currentTick;
			lastXpCreditTick = currentTick;
		}
	}

	/**
	 * SPEC.md §16: correlates this tick's inventory deltas against the
	 * current session. Confirmed pickups (§20a) are resolved first and
	 * removed from the increase pool, so the same inventory increase can
	 * never be double-counted as both a pickup and direct acquisition -
	 * a real risk since a Take click and a fresh qualifying XP event can
	 * land close together (e.g. looting right after a Slayer kill).
	 * Whatever's left is attributed as direct acquisition only within a
	 * small window of ticks after XP was last credited (§16 [v7 fix]) -
	 * inventory gained well outside that window isn't "generated" in
	 * Phase 2/3's sense.
	 */
	private void creditItemFlow()
	{
		Map<Integer, Integer> increased = inventoryDeltaTracker.consumeIncreased();
		Map<Integer, Integer> decreased = inventoryDeltaTracker.consumeDecreased();

		if (!increased.isEmpty() || !decreased.isEmpty())
		{
			log.debug("Inventory delta at tick {}: increased={} decreased={} (lastXpCreditTick={}, state={})",
				currentTick, increased, decreased, lastXpCreditTick, state);
		}

		if (state == SessionState.CANDIDATE && currentTick - lastCandidateXpTick <= GENERATION_WINDOW_TICKS)
		{
			for (Map.Entry<Integer, Integer> entry : increased.entrySet())
			{
				candidateGeneratedBuffer.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		if ((state != SessionState.ACTIVE && state != SessionState.PAUSED) || currentSession == null)
		{
			return;
		}

		Map<Integer, Integer> confirmedPickups = pickupCorrelator.resolve(currentTick, increased);
		for (Map.Entry<Integer, Integer> entry : confirmedPickups.entrySet())
		{
			increased.merge(entry.getKey(), -entry.getValue(), Integer::sum);
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
				if (entry.getValue() <= 0)
				{
					continue;
				}
				log.debug("Crediting generated: itemId={} qty={}", entry.getKey(), entry.getValue());
				currentSession.addGenerated(entry.getKey(), entry.getValue());
			}
		}

		Map<Integer, Integer> confirmedDrops = dropCorrelator.resolve(currentTick, decreased);
		for (Map.Entry<Integer, Integer> entry : confirmedDrops.entrySet())
		{
			// drops are click-gated and therefore more specific than the
			// bank signature below - claim them out of the decrease pool
			// first so one inventory decrease can't read as both
			decreased.merge(entry.getKey(), -entry.getValue(), Integer::sum);
			currentSession.addDropped(entry.getKey(), entry.getValue());
		}
		if (!confirmedDrops.isEmpty())
		{
			// SPEC.md §13 [v4]: a drop is deliberate activity, not idle time
			recordNonXpActivity();
		}

		Map<Integer, Integer> confirmedBanked = bankCorrelator.resolve(decreased,
			itemId -> currentSession.getOutstandingForBanking(itemId));
		for (Map.Entry<Integer, Integer> entry : confirmedBanked.entrySet())
		{
			log.debug("Crediting banked: itemId={} qty={}", entry.getKey(), entry.getValue());
			// the correlator reads the decrease pool without consuming it,
			// so claim it here - otherwise a deposit would also be counted
			// as consumption below
			decreased.merge(entry.getKey(), -entry.getValue(), Integer::sum);
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
	private void creditConsumption(Map<Integer, Integer> decreased)
	{
		if (currentTick - lastXpCreditTick > GENERATION_WINDOW_TICKS)
		{
			return;
		}

		Map<Integer, Integer> equipped = equipmentDeltaTracker.consumeIncreased();
		for (Map.Entry<Integer, Integer> entry : decreased.entrySet())
		{
			int qty = entry.getValue() - equipped.getOrDefault(entry.getKey(), 0);
			if (qty <= 0)
			{
				continue;
			}
			log.debug("Crediting consumed: itemId={} qty={}", entry.getKey(), qty);
			currentSession.addConsumed(entry.getKey(), qty);
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

		if (state == SessionState.PAUSED && currentSession != null && currentSession.getSkill() == groupKey)
		{
			currentSession.addXp(skill, delta);
			recordActionIfMeaningful();
			lastQualifyingTick = currentTick;
			lastXpCreditTick = currentTick;
			state = SessionState.ACTIVE; // [v2] resume threshold = 1 event, distinct from start threshold
			return;
		}

		if (state == SessionState.ACTIVE || state == SessionState.PAUSED || state == SessionState.PROMPTED)
		{
			// a session/prompt for a different tracking group is already in flight
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

		if (state != SessionState.CANDIDATE)
		{
			log.debug("Candidate opened: skill={} groupKey={} tick={}", skill, groupKey, currentTick);
		}
		candidateGroupKey = groupKey;
		state = SessionState.CANDIDATE;
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
		dropCorrelator.reset();
		pickupCorrelator.reset();
		bankCorrelator.reset();
		lastQualifyingTick = currentTick;
		lastXpCreditTick = currentTick;
		lastRemainingAmount = -1;

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

	public void pause()
	{
		if (state == SessionState.ACTIVE)
		{
			state = SessionState.PAUSED;
		}
	}

	public void resume()
	{
		if (state == SessionState.PAUSED)
		{
			state = SessionState.ACTIVE;
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
		dropCorrelator.reset();
		pickupCorrelator.reset();
		bankCorrelator.reset();
		state = SessionState.IDLE;
	}
}
