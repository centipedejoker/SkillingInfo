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
