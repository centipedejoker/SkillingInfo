package com.skillinginfo.session;

import net.runelite.api.Skill;
import org.junit.Test;
import static com.skillinginfo.session.SessionManagerHarness.startedSession;
import static org.junit.Assert.assertEquals;

/**
 * §37's kill counting, driven through the real state machine - the session
 * state an update arrives in is the whole point here, so none of this is
 * reachable from {@link ActivitySession} directly (see
 * {@link SlayerSessionTest} for the model-level arithmetic).
 * <p>
 * Kills are derived from the change in the task's remaining count, never
 * from its totals, so everything below is about which changes the session
 * is entitled to claim.
 */
public class SessionManagerSlayerTest
{
	private static final String TASK = "Bloodveld";
	private static final String LOCATION = "Slayer Tower";

	private static SessionManager combatSession()
	{
		// §7a: SLAYER is the combat group key, and §1a requires real Slayer
		// XP before the gate opens
		return startedSession(Skill.SLAYER);
	}

	@Test
	public void aPartFinishedTaskContributesNothingRetroactively()
	{
		// §37/§14: the trap that made XP Tracker's rates unusable. The first
		// observation is a baseline, not 40 kills.
		SessionManager m = combatSession();
		m.onSlayerTaskUpdate(TASK, LOCATION, 40);

		assertEquals("joining a task mid-way counts none of it", 0, m.getCurrentSession().getKills());

		m.onSlayerTaskUpdate(TASK, LOCATION, 38);
		assertEquals(2, m.getCurrentSession().getKills());
	}

	@Test
	public void killsDuringAPauseAreCountedAndResumeTheSession()
	{
		// `[v8]` This was the one signal that both discarded its evidence and
		// advanced the baseline past it, so the kill was lost rather than
		// deferred - while loot from the same kill was credited and did
		// resume the session.
		SessionManager m = combatSession();
		m.onSlayerTaskUpdate(TASK, LOCATION, 40);

		m.pause();
		assertEquals(SessionState.PAUSED, m.getState());

		m.onSlayerTaskUpdate(TASK, LOCATION, 39);

		assertEquals("a kill is a kill whatever the session state", 1, m.getCurrentSession().getKills());
		assertEquals("and it proves the session is still in progress",
			SessionState.ACTIVE, m.getState());
	}

	@Test
	public void killsAfterAPauseAreNotLostToAnAdvancedBaseline()
	{
		// the failure was silent and cumulative: every kill that landed in a
		// pause moved the baseline on, so nothing was ever caught up
		SessionManager m = combatSession();
		m.onSlayerTaskUpdate(TASK, LOCATION, 40);

		m.pause();
		m.onSlayerTaskUpdate(TASK, LOCATION, 39);
		m.onSlayerTaskUpdate(TASK, LOCATION, 38);

		assertEquals(2, m.getCurrentSession().getKills());
	}

	@Test
	public void aNewTaskRebaselinesInsteadOfRecordingANegative()
	{
		SessionManager m = combatSession();
		m.onSlayerTaskUpdate(TASK, LOCATION, 5);
		m.onSlayerTaskUpdate(TASK, LOCATION, 3);

		// task finished and a new, larger one assigned
		m.onSlayerTaskUpdate("Abyssal demons", "Slayer Tower", 120);
		assertEquals("a rise is a new task, not negative progress", 2, m.getCurrentSession().getKills());

		m.onSlayerTaskUpdate("Abyssal demons", "Slayer Tower", 119);
		assertEquals(3, m.getCurrentSession().getKills());
	}

	@Test
	public void theTaskNamesTheActivity()
	{
		// §16: for combat the task beats anything the item output could suggest
		SessionManager m = combatSession();
		m.onSlayerTaskUpdate(TASK, LOCATION, 40);

		assertEquals("Bloodveld, Slayer Tower", m.getCurrentSession().getActivity());
	}

	@Test
	public void aGatheringSessionIgnoresTaskProgressEntirely()
	{
		// a task can tick down while the player is chopping - §7a keeps the
		// two apart, and kills belong to the combat group only
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.onSlayerTaskUpdate(TASK, LOCATION, 40);
		m.onSlayerTaskUpdate(TASK, LOCATION, 38);

		assertEquals(0, m.getCurrentSession().getKills());
	}
}
