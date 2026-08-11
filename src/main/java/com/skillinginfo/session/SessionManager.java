package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import net.runelite.api.Skill;

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
public class SessionManager
{
	private final SkillingInfoConfig config;
	private final SessionRepository repository;
	private final XpTracker xpTracker = new XpTracker();
	private final SessionClock clock = new SessionClock();

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

	public SessionManager(SkillingInfoConfig config, SessionRepository repository)
	{
		this.config = config;
		this.repository = repository;
	}

	public void init()
	{
		history.clear();
		history.addAll(repository.loadAll());
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
		boolean rewardBurstTick = groupKeysThisTick.size() > 1;

		for (Map.Entry<Skill, Integer> entry : pendingTickDeltas.entrySet())
		{
			if (rewardBurstTick)
			{
				creditActiveSessionOnly(entry.getKey(), entry.getValue());
			}
			else
			{
				processQualifyingEvent(entry.getKey(), entry.getValue());
			}
		}
		pendingTickDeltas.clear();

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
		}
	}

	private void processQualifyingEvent(Skill skill, int delta)
	{
		Skill groupKey = TrackingGroups.groupKey(skill);

		if (state == SessionState.ACTIVE && currentSession != null && currentSession.getSkill() == groupKey)
		{
			currentSession.addXp(skill, delta);
			lastQualifyingTick = currentTick;
			return;
		}

		if (state == SessionState.PAUSED && currentSession != null && currentSession.getSkill() == groupKey)
		{
			currentSession.addXp(skill, delta);
			lastQualifyingTick = currentTick;
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

		candidateGroupKey = groupKey;
		state = SessionState.CANDIDATE;
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
		if (summary.isPresent())
		{
			pendingPrompt = summary.get();
			promptExpiresAtTick = currentTick + CandidateDetector.secondsToTicks(config.promptTimeoutSeconds());
			state = SessionState.PROMPTED;
		}
	}

	private void tickPrompted()
	{
		if (currentTick >= promptExpiresAtTick)
		{
			// SPEC.md §10: expiry discards the buffer
			buffers.remove(candidateGroupKey);
			candidateGroupKey = null;
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
			for (QualifyingXpEvent event : buffer.events())
			{
				session.addXp(event.getSkill(), event.getXpDelta());
			}
		}
		else
		{
			session.setStartedAt(Instant.now());
		}

		currentSession = session;
		clock.reset();
		lastQualifyingTick = currentTick;

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

		currentSession.setEndedAt(Instant.now());
		currentSession.setActiveSeconds(clock.getActiveSeconds());
		currentSession.setIdleSeconds(clock.getIdleSeconds());
		currentSession.setTotalSeconds(clock.getTotalSeconds());

		repository.append(currentSession);
		history.add(0, currentSession);

		currentSession = null;
		clock.reset();
		state = SessionState.IDLE;
	}
}
