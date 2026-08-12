package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static com.skillinginfo.session.SessionManagerHarness.STARTED_TICK;
import static com.skillinginfo.session.SessionManagerHarness.autoPause;
import static com.skillinginfo.session.SessionManagerHarness.entry;
import static com.skillinginfo.session.SessionManagerHarness.items;
import static com.skillinginfo.session.SessionManagerHarness.startedSession;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §13a: a manual pause is an instruction and an auto-pause is a guess, so
 * they end differently. Everything the player does during an auto-pause is
 * evidence that the guess was wrong and resumes the session; nothing they do
 * during a manual pause is evidence against having asked for it.
 * <p>
 * A manual pause also records nothing at all. That follows from the clock:
 * crediting XP against a frozen active time would make every rate climb for
 * as long as the pause lasted.
 */
public class SessionManagerManualPauseTest
{
	private static final int LOGS = ItemID.LOGS;

	private static int generated(SessionManager m, int itemId)
	{
		ItemFlowEntry entry = entry(m, itemId);
		return entry == null ? 0 : entry.getGenerated();
	}

	private static int xp(SessionManager m)
	{
		return m.getCurrentSession().getXpGained(m.getCurrentSession().getSkill());
	}

	// ------------------------------------------------------------------
	// Stickiness
	// ------------------------------------------------------------------

	@Test
	public void qualifyingXpDoesNotLiftAManualPause()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("only Resume ends a manual pause", SessionState.PAUSED, m.getState());
		assertTrue(m.isManuallyPaused());
	}

	@Test
	public void qualifyingXpDoesLiftAnAutoPause()
	{
		// the §7 [v2] resume threshold, which must survive all of the above
		SessionManager m = startedSession(Skill.WOODCUTTING);
		int tick = autoPause(m);

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onGameTick(tick + 1);

		assertEquals(SessionState.ACTIVE, m.getState());
	}

	@Test
	public void itemFlowDoesNotLiftAManualPause()
	{
		// §13 [v4] broadened the idle signal past XP so a long banking trip
		// wouldn't auto-pause a live session - that reasoning doesn't apply
		// to a pause the player asked for
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();

		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(SessionState.PAUSED, m.getState());
	}

	@Test
	public void resumeClearsTheStickiness()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();
		m.resume();

		assertEquals(SessionState.ACTIVE, m.getState());
		assertFalse(m.isManuallyPaused());
	}

	@Test
	public void aFreshSessionIsNeverBornPaused()
	{
		// the flag outliving its session would silently freeze the next one
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();
		m.stop();

		SessionManager next = startedSession(Skill.WOODCUTTING);
		assertFalse(next.isManuallyPaused());
		assertEquals(SessionState.ACTIVE, next.getState());
	}

	// ------------------------------------------------------------------
	// Nothing is recorded, and nothing arrives in a lump afterwards
	// ------------------------------------------------------------------

	@Test
	public void xpDuringAManualPauseIsNotCredited()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING);
		int before = xp(m);
		m.pause();

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("a frozen clock with live XP is how rates inflate", before, xp(m));
	}

	@Test
	public void outputDuringAManualPauseIsNotCredited()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(0, generated(m, LOGS));
	}

	@Test
	public void whatHappenedDuringThePauseDoesNotArriveOnResume()
	{
		// the pools still drain every tick while frozen (§18 `[v8]`), so the
		// paused stretch is dropped rather than deferred into the next tick
		// that counts
		SessionManager m = startedSession(Skill.WOODCUTTING);
		m.pause();

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		m.resume();
		m.onStatChanged(Skill.WOODCUTTING, 500);
		m.onInventoryChanged(items(LOGS, 2)); // one further log cut
		m.onGameTick(STARTED_TICK + 2);

		assertEquals("only the log cut after resuming", 1, generated(m, LOGS));
	}

	@Test
	public void killsDuringAManualPauseAreNotCounted()
	{
		SessionManager m = startedSession(Skill.SLAYER);
		m.onSlayerTaskUpdate("Bloodveld", "Slayer Tower", 40);
		m.pause();

		m.onSlayerTaskUpdate("Bloodveld", "Slayer Tower", 37);

		assertEquals(0, m.getCurrentSession().getKills());
		assertEquals(SessionState.PAUSED, m.getState());

		// and they don't arrive in a lump either - the baseline moved with
		// them, deliberately, because the player asked for that stretch not
		// to count
		m.resume();
		m.onSlayerTaskUpdate("Bloodveld", "Slayer Tower", 36);
		assertEquals("only the kill after resuming", 1, m.getCurrentSession().getKills());
	}

	@Test
	public void lootDuringAManualPauseIsNotCredited()
	{
		SessionManager m = startedSession(Skill.SLAYER);
		m.pause();

		m.onNpcLootReceived(java.util.Collections.singletonMap(ItemID.DEATHRUNE, 50));

		assertEquals(0, m.getCurrentSession().getTotalGenerated());
		assertEquals(SessionState.PAUSED, m.getState());
	}
}
