package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static com.skillinginfo.session.SessionManagerHarness.entry;
import static com.skillinginfo.session.SessionManagerHarness.items;
import static com.skillinginfo.session.SessionManagerHarness.manager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The detect → prompt → start path (§7, §10), and specifically what happens
 * to work done <em>before</em> Start is pressed.
 * <p>
 * §6/§10 make retroactive start a deliberate feature: the session is
 * back-dated to the first buffered drop so the player isn't punished for
 * taking a moment to decide. That only holds together if the buffers cover
 * the whole of the span {@code startedAt} claims.
 */
public class SessionManagerLifecycleTest
{
	private static final int LOGS = ItemID.LOGS;

	private static SessionManager seeded()
	{
		SessionManager m = manager();
		m.onInventoryChanged(items());
		m.onEquipmentChanged(items());
		m.onBankChanged(items());
		m.onGameTick(0);
		return m;
	}

	@Test
	public void workDoneWhileThePromptIsUpIsKept()
	{
		// PROMPTED used to return early from processQualifyingEvent, so XP,
		// actions and output produced while the offer sat on screen were
		// dropped outright - while start() went on to back-date startedAt
		// across that window regardless. With the default 15s timeout that
		// is up to fifteen seconds of work missing from a record whose own
		// clock says it was there.
		SessionManager m = seeded();
		int xp = 0;
		int qty = 0;
		int tick = 0;
		m.onStatChanged(Skill.WOODCUTTING, 0); // login sync

		for (int i = 0; i < 3; i++) // reach the gate
		{
			tick += 2;
			xp += 100;
			qty++;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onInventoryChanged(items(LOGS, qty));
			m.onGameTick(tick);
		}
		assertEquals(SessionState.PROMPTED, m.getState());
		int atGate = m.getPendingPrompt().getTotalXp();

		for (int i = 0; i < 5; i++) // keep chopping while deciding
		{
			tick += 2;
			xp += 100;
			qty++;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onInventoryChanged(items(LOGS, qty));
			m.onGameTick(tick);
		}
		assertEquals("a prompt for this same group must not be cut short",
			SessionState.PROMPTED, m.getState());

		m.start();
		ActivitySession s = m.getCurrentSession();
		assertEquals("every drop is kept, whichever side of Start it fell on",
			800, s.getXpGained(Skill.WOODCUTTING));
		assertEquals(8, s.getActions());
		assertEquals(8, entry(m, LOGS).getGenerated());

		long backdatedSeconds = java.time.Duration
			.between(s.getStartedAt(), java.time.Instant.now()).getSeconds();
		assertTrue("and startedAt covers exactly the span that was recorded",
			backdatedSeconds >= 8);
		assertTrue(atGate < 800);
	}

	@Test
	public void thePromptsOwnFigureKeepsPaceWithTheBuffer()
	{
		// The offer is a promise about what accepting it will record. Freezing
		// it at the moment the gate was met would advertise one number and
		// hand over another the instant Start was pressed - §14 [v9]'s defect,
		// one screen earlier.
		SessionManager m = seeded();
		int xp = 0;
		int tick = 0;
		m.onStatChanged(Skill.WOODCUTTING, 0);

		for (int i = 0; i < 3; i++)
		{
			tick += 2;
			xp += 100;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onGameTick(tick);
		}
		assertEquals(300, m.getPendingPrompt().getTotalXp());

		tick += 2;
		m.onStatChanged(Skill.WOODCUTTING, xp + 100);
		m.onGameTick(tick);

		assertEquals("the offer moved with the buffer", 400, m.getPendingPrompt().getTotalXp());
		assertEquals(4, m.getPendingPrompt().getDropCount());
	}

	@Test
	public void switchingAccountsDoesNotInjectTheXpDifferenceBetweenThem()
	{
		// XpTracker holds each skill's last-seen total, so a stale baseline
		// doesn't decay - it turns the next account's first sync into one
		// enormous gain. Logging into a 10m Woodcutting main after a 500k alt
		// offered "+9,500,200 XP" and would have written it to an append-only
		// history file. XpTracker.reset() existed for this and had no callers.
		SessionManager m = seeded();
		m.onStatChanged(Skill.WOODCUTTING, 500_000); // alt, first sync
		m.onGameTick(1);
		m.onLogout();

		m.onStatChanged(Skill.WOODCUTTING, 10_000_000); // main, re-sync
		m.onGameTick(2);
		m.onStatChanged(Skill.WOODCUTTING, 10_000_100);
		m.onGameTick(4);
		m.onStatChanged(Skill.WOODCUTTING, 10_000_200);
		m.onGameTick(6);

		// the re-sync is a sync, not a gain, so only two real drops follow -
		// one short of the gate
		assertEquals("no session offered on the strength of a phantom",
			SessionState.CANDIDATE, m.getState());
		assertNull(m.getPendingPrompt());

		m.onStatChanged(Skill.WOODCUTTING, 10_000_300);
		m.onGameTick(8);
		assertEquals(SessionState.PROMPTED, m.getState());
		assertEquals("worth what was actually gained", 300, m.getPendingPrompt().getTotalXp());
	}

	// ------------------------------------------------------------------
	// §9's reward-burst filter must not reject ordinary methods
	// ------------------------------------------------------------------

	/** Control: plain fishing reaches the prompt, so the driver below is sound. */
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

	@Test
	public void barbarianFishingReachesThePrompt()
	{
		// Fishing + Agility + Strength on one catch is three group keys, so
		// resolvePrimary returned null and every catch read as a §9 reward
		// burst - forever. A player could barbarian-fish for an hour and
		// never leave "Nothing worth tracking yet", with no error and no log
		// line. Same failure as the infernal axe in §9 [v7], one step out.
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
		assertEquals(SessionState.PROMPTED, m.getState());
		assertEquals("and it's a fishing session, not an agility one",
			Skill.FISHING, m.getPendingPrompt().getSkill());
	}

	@Test
	public void birdhouseDismantlingReachesThePrompt()
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
		assertEquals(SessionState.PROMPTED, m.getState());
		assertEquals(Skill.HUNTER, m.getPendingPrompt().getSkill());
	}

	@Test
	public void aGenuineRewardBurstIsStillRejected()
	{
		// the behaviour §9 exists for, which the entries above must not
		// loosen: three unrelated skills at once is a reward interface
		SessionManager m = seeded();
		for (int t = 1; t <= 200; t += 5)
		{
			m.onStatChanged(Skill.MINING, t * 100);
			m.onStatChanged(Skill.CRAFTING, t * 100);
			m.onStatChanged(Skill.HERBLORE, t * 100);
			m.onGameTick(t);
		}
		assertEquals(SessionState.IDLE, m.getState());
	}

	@Test
	public void aPromptForOneGroupStillBlocksAnother()
	{
		// the §7a single-slot rule the above must not loosen: only the group
		// being offered goes on collecting
		SessionManager m = seeded();
		int xp = 0;
		int tick = 0;
		m.onStatChanged(Skill.WOODCUTTING, 0);
		m.onStatChanged(Skill.FISHING, 0);

		for (int i = 0; i < 3; i++)
		{
			tick += 2;
			xp += 100;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onGameTick(tick);
		}
		assertEquals(SessionState.PROMPTED, m.getState());

		for (int i = 0; i < 5; i++)
		{
			tick += 2;
			m.onStatChanged(Skill.FISHING, (i + 1) * 100);
			m.onGameTick(tick);
		}

		assertEquals("the woodcutting offer stands", Skill.WOODCUTTING, m.getPendingPrompt().getSkill());
		assertEquals("and fishing didn't sneak into its buffer", 300,
			m.getPendingPrompt().getTotalXp());

		m.start();
		assertEquals(0, m.getCurrentSession().getXpGained(Skill.FISHING));
	}
}
