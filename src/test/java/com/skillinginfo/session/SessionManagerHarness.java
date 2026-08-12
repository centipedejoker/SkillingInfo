package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;

/**
 * Shared scaffolding for tests that drive the real {@link SessionManager}
 * state machine rather than poking {@link ActivitySession} directly.
 * <p>
 * Worth the extraction because the defects this covers are only visible at
 * the tick level: they need two containers moving in the same tick, or a
 * session sitting in a particular state when an event arrives. No mocking
 * framework is needed - {@link SkillingInfoConfig} is all-defaults, and the
 * two collaborators are either trivially subclassed or never touched.
 */
final class SessionManagerHarness
{
	/** The tick {@link #startedSession} leaves a started session sitting on. */
	static final int STARTED_TICK = 5;

	private SessionManagerHarness()
	{
	}

	static SessionManager manager()
	{
		SkillingInfoConfig config = new SkillingInfoConfig()
		{
		};
		SessionRepository repository = new SessionRepository(null)
		{
			@Override
			public void append(ActivitySession session)
			{
			}

			@Override
			public List<ActivitySession> loadAll()
			{
				return new ArrayList<>();
			}
		};
		// ItemUseStore's ConfigManager is only touched when a session is
		// finalised, which no test here does
		return new SessionManager(config, repository, new ItemUseStore(null));
	}

	static Item[] items(int... idQtyPairs)
	{
		Item[] result = new Item[idQtyPairs.length / 2];
		for (int i = 0; i < result.length; i++)
		{
			result[i] = new Item(idQtyPairs[i * 2], idQtyPairs[i * 2 + 1]);
		}
		return result;
	}

	/**
	 * Seeds all three container baselines, then drives detection to a prompt
	 * and starts the session. Leaves it ACTIVE at {@link #STARTED_TICK} with
	 * XP credited on that tick, so the caller opens on an XP window that is
	 * still inside {@code GENERATION_WINDOW_TICKS}.
	 * <p>
	 * Pass {@link Skill#SLAYER} for a combat session: it is the combat group
	 * key (§7a), and §1a additionally requires real Slayer XP in the buffer
	 * before the gate can be met, which feeding it directly satisfies.
	 */
	static SessionManager startedSession(Skill skill, Item[] inventory, Item[] worn, Item[] bank)
	{
		SessionManager m = manager();
		m.onInventoryChanged(inventory);
		m.onEquipmentChanged(worn);
		m.onBankChanged(bank);
		m.onStatChanged(skill, 0); // first observation is the sync, not a gain
		m.onGameTick(0);           // discards the seeding deltas while IDLE

		m.onStatChanged(skill, 100);
		m.onGameTick(1);
		m.onStatChanged(skill, 200);
		m.onGameTick(3);
		m.onStatChanged(skill, 300);
		m.onGameTick(STARTED_TICK);

		assertEquals("three drops spanning four ticks should reach the gate",
			SessionState.PROMPTED, m.getState());
		m.start();
		assertEquals(SessionState.ACTIVE, m.getState());
		return m;
	}

	static SessionManager startedSession(Skill skill)
	{
		return startedSession(skill, items(), items(), items());
	}

	/**
	 * Idles the session past the default five-minute threshold so it
	 * auto-pauses (§13) - as distinct from {@link SessionManager#pause()},
	 * which is the player's instruction and behaves differently (§13a).
	 *
	 * @return the tick it left the session on, for the caller to carry on from
	 */
	static int autoPause(SessionManager m)
	{
		int tick = STARTED_TICK + 600; // threshold is 300s ≈ 500 ticks
		m.onGameTick(tick);
		assertEquals("no qualifying activity for five minutes should auto-pause",
			SessionState.PAUSED, m.getState());
		return tick;
	}

	static ItemFlowEntry entry(SessionManager m, int itemId)
	{
		for (ItemFlowEntry candidate : m.getCurrentSession().getItemFlow())
		{
			if (candidate.getItemId() == itemId)
			{
				return candidate;
			}
		}
		return null;
	}
}
