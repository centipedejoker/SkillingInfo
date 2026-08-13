package com.skillinginfo.session;

import static com.skillinginfo.session.SessionManagerHarness.entry;
import static com.skillinginfo.session.SessionManagerHarness.items;
import static com.skillinginfo.session.SessionManagerHarness.manager;
import static com.skillinginfo.session.SessionManagerHarness.startedSession;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Reproductions for the red-team review. EVERY assertion marked "bug:" pins
 * the CURRENT, WRONG behaviour, so this class passes as-is on the unfixed
 * code. To work a finding: flip its "bug:" assertions to the correct value,
 * watch the test fail, fix the production code, watch it pass.
 */
public class RedTeamReproTest
{
	private static final int RAW_SHARK = 383;
	private static final int RAW_SHARK_NOTED = 384;
	private static final int COAL = 453;
	private static final int LOGS = 1511;
	private static final int COINS = 995;

	// =============================================================
	// F10 - actions awarding three tracking groups never start
	// =============================================================

	/** Control: plain fishing does reach the prompt, so the driver is sound. */
	@Test
	public void plainFishingReachesPrompt()
	{
		SessionManager m = seeded();
		int fx = 0;
		for (int t = 1; t <= 16; t += 5)
		{
			fx += 100;
			m.onStatChanged(Skill.FISHING, fx);
			m.onGameTick(t);
		}
		assertEquals(SessionState.PROMPTED, m.getState());
	}

	/**
	 * Barbarian fishing awards Fishing + Agility + Strength on the same tick.
	 * groupKeys = {FISHING, AGILITY, SLAYER}; resolvePrimary returns null, so
	 * every catch is treated as a §9 reward burst and no candidate opens.
	 */
	@Test
	public void barbarianFishingNeverOffersASession()
	{
		SessionManager m = seeded();
		int fx = 0;
		int ax = 0;
		int sx = 0;
		for (int t = 1; t <= 200; t += 5)
		{
			fx += 100;
			ax += 8;
			sx += 8;
			m.onStatChanged(Skill.FISHING, fx);
			m.onStatChanged(Skill.AGILITY, ax);
			m.onStatChanged(Skill.STRENGTH, sx);
			m.onGameTick(t);
		}
		assertEquals("bug: never leaves IDLE", SessionState.IDLE, m.getState());
	}

	/** Same shape: birdhouse dismantling awards Hunter + Crafting in one tick. */
	@Test
	public void hunterPlusCraftingNeverOffersASession()
	{
		SessionManager m = seeded();
		int hx = 0;
		int cx = 0;
		for (int t = 1; t <= 200; t += 5)
		{
			hx += 100;
			cx += 30;
			m.onStatChanged(Skill.HUNTER, hx);
			m.onStatChanged(Skill.CRAFTING, cx);
			m.onGameTick(t);
		}
		assertEquals("bug: never leaves IDLE", SessionState.IDLE, m.getState());
	}

	// =============================================================
	// F11 - the combat XP headline is Slayer XP only
	// =============================================================

	/**
	 * The prompt sums every buffered skill; the session tile reads only the
	 * group key's XP. Press Start and the number collapses with no explanation.
	 */
	@Test
	public void combatXpHeadlineCollapsesTheMomentStartIsPressed()
	{
		SessionManager m = seeded();
		int atk = 0;
		int slay = 0;
		for (int t = 1; t <= 6; t += 2)
		{
			atk += 400;
			slay += 100;
			m.onStatChanged(Skill.ATTACK, atk);
			m.onStatChanged(Skill.HITPOINTS, atk);
			m.onStatChanged(Skill.SLAYER, slay);
			m.onGameTick(t);
		}
		assertEquals(SessionState.PROMPTED, m.getState());
		int promptTotal = m.getPendingPrompt().getTotalXp();
		assertEquals(800 + 800 + 200, promptTotal);

		m.start();
		ActivitySession s = m.getCurrentSession();
		assertEquals("bug: the XP tile shows only the Slayer share", 200, s.getXpGained(s.getSkill()));
		assertTrue(promptTotal > 8 * s.getXpGained(s.getSkill()));
	}

	// =============================================================
	// F12 - a stale Take click steals later skilling output
	// =============================================================

	/**
	 * A "Take" click that never resolves stays pending for 30 ticks and then
	 * claims the next inventory increase of that item - which, while chopping,
	 * is a log you cut. netRetained counts pickedUp; getRetentionRate()'s
	 * denominator does not, so the headline goes above 100%.
	 */
	@Test
	public void staleTakeClickStealsGeneratedOutputAndBreaksRetention()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING);
		int xp = 300;
		int tick = SessionManagerHarness.STARTED_TICK;
		for (int i = 1; i <= 4; i++) // chop four logs normally
		{
			tick += 2;
			xp += 100;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onInventoryChanged(items(LOGS, i));
			m.onGameTick(tick);
		}
		assertEquals(4, entry(m, LOGS).getGenerated());

		m.onTakeClicked(LOGS); // ground logs; the pickup never happens
		tick += 10;            // still inside PickupCorrelator's 30-tick window
		xp += 100;
		m.onStatChanged(Skill.WOODCUTTING, xp);
		m.onInventoryChanged(items(LOGS, 5)); // fifth log CHOPPED, not picked up
		m.onGameTick(tick);

		ItemFlowEntry logs = entry(m, LOGS);
		assertEquals("bug: the chopped log was booked as a ground pickup", 1, logs.getPickedUp());
		assertEquals("bug: generated undercounts by one", 4, logs.getGenerated());
		assertEquals(5, logs.getNetRetained());

		double retention = m.getCurrentSession().getRetentionRate();
		assertTrue("bug: panel renders " + String.format("%.1f%%", retention * 100),
			retention > 1.0);
	}

	// =============================================================

	private static SessionManager seeded()
	{
		SessionManager m = manager();
		m.onInventoryChanged(items());
		m.onEquipmentChanged(items());
		m.onBankChanged(items());
		m.onGameTick(0);
		return m;
	}
}
