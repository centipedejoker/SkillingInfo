package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Item;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Tick-ordering coverage for {@link SessionManager#creditItemFlow} - the one
 * place where a single inventory movement could be counted twice, or counted
 * at all when it isn't a gain or a loss.
 * <p>
 * Everything here drives the real state machine (detect → prompt → start →
 * tick) rather than poking {@link ActivitySession} directly, because the
 * three bugs these tests were written for were all invisible at the model
 * level: each one needed two containers moving in the same tick to show up.
 * <p>
 * The catch-all rules at the end of {@code creditItemFlow} credit
 * <em>anything</em> unexplained that moves inside the XP window, so the
 * tests that matter most are the ones asserting a movement is explained -
 * an item travelling between the inventory and another container is not
 * output, and not consumption.
 */
public class SessionManagerItemFlowTest
{
	private static final int AXE = ItemID.RUNE_AXE;
	private static final int GLORY = ItemID.AMULET_OF_GLORY;
	private static final int LOGS = ItemID.LOGS;
	private static final int RAW_SHARK = ItemID.RAW_SHARK;

	/** The tick the helper below leaves a started session sitting on. */
	private static final int STARTED_TICK = 5;

	private static SessionManager manager()
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
		// finalised, which none of these tests do
		return new SessionManager(config, repository, new ItemUseStore(null));
	}

	private static Item[] items(int... idQtyPairs)
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
	 */
	private static SessionManager startedSession(Skill skill, Item[] inventory, Item[] worn, Item[] bank)
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

	private static ItemFlowEntry entry(SessionManager m, int itemId)
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

	private static int generated(SessionManager m, int itemId)
	{
		ItemFlowEntry entry = entry(m, itemId);
		return entry == null ? 0 : entry.getGenerated();
	}

	private static int consumed(SessionManager m, int itemId)
	{
		ItemFlowEntry entry = entry(m, itemId);
		return entry == null ? 0 : entry.getConsumed();
	}

	// ------------------------------------------------------------------
	// Movements between containers are not gains or losses
	// ------------------------------------------------------------------

	@Test
	public void unequippingIsNotSkillingOutput()
	{
		// §18 [v7] fixed the wield direction - an item leaving the inventory
		// for the worn container isn't consumption. The mirror was missing:
		// taking a glory off mid-chop is an inventory increase inside the XP
		// window, which the generation rule booked as woodcutting output.
		SessionManager m = startedSession(Skill.WOODCUTTING, items(), items(GLORY, 1), items());

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onEquipmentChanged(items());
		m.onInventoryChanged(items(GLORY, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("a glory is not a log", 0, generated(m, GLORY));
		assertEquals("and it isn't account gain either", 0, m.getCurrentSession().getTotalNetRetained());
	}

	@Test
	public void wieldingIsStillNotConsumption()
	{
		// the §18 [v7] behaviour the above must not regress
		SessionManager m = startedSession(Skill.WOODCUTTING, items(AXE, 1), items(), items());

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items());
		m.onEquipmentChanged(items(AXE, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("wielding an axe doesn't destroy it", 0, consumed(m, AXE));
	}

	@Test
	public void bankWithdrawalIsNotSkillingOutput()
	{
		// The deposit direction has always been capped by §25a's three-way
		// minimum; withdrawal had no counterpart at all. Any bank-adjacent
		// skill - Cooking, Smithing, Runecraft - can withdraw within the
		// generation window and have the whole load booked as produced.
		SessionManager m = startedSession(Skill.COOKING, items(), items(), items(RAW_SHARK, 100));

		m.onStatChanged(Skill.COOKING, 400);
		m.onBankChanged(items(RAW_SHARK, 73));
		m.onInventoryChanged(items(RAW_SHARK, 27));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("withdrawing raw sharks is not catching them", 0, generated(m, RAW_SHARK));
	}

	@Test
	public void aWithdrawalAndRealOutputOfTheSameItemAreSeparated()
	{
		// the claim is quantity-aware, not all-or-nothing: 27 of the 28 logs
		// that appeared are explained by the bank, the 28th is not
		SessionManager m = startedSession(Skill.WOODCUTTING, items(), items(), items(LOGS, 100));

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onBankChanged(items(LOGS, 73));
		m.onInventoryChanged(items(LOGS, 28));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("only the log that wasn't withdrawn was cut", 1, generated(m, LOGS));
	}

	@Test
	public void anEquipOutsideTheXpWindowDoesNotCancelALaterConsumption()
	{
		// creditConsumption used to return before draining the equipment
		// pool, so an equip outside the XP window sat there indefinitely and
		// silently cancelled the next consumption of that item.
		SessionManager m = startedSession(Skill.WOODCUTTING, items(AXE, 1), items(), items());

		// well past the XP window: equip the axe
		m.onInventoryChanged(items());
		m.onEquipmentChanged(items(AXE, 1));
		m.onGameTick(20);

		// a second axe arrives, still outside the window so it isn't output
		m.onInventoryChanged(items(AXE, 1));
		m.onGameTick(24);

		// XP resumes and that axe is genuinely used up
		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items());
		m.onGameTick(25);

		assertEquals("the equip five ticks earlier explains nothing here", 1, consumed(m, AXE));
	}

	// ------------------------------------------------------------------
	// Controls: the catch-all rules still fire on real movements
	// ------------------------------------------------------------------

	@Test
	public void realOutputIsStillCreditedAsGenerated()
	{
		SessionManager m = startedSession(Skill.WOODCUTTING, items(), items(), items());

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(1, generated(m, LOGS));
		assertEquals(1, m.getCurrentSession().getTotalNetRetained());
	}

	@Test
	public void realConsumptionIsStillCredited()
	{
		SessionManager m = startedSession(Skill.COOKING, items(RAW_SHARK, 1), items(), items());

		m.onStatChanged(Skill.COOKING, 400);
		m.onInventoryChanged(items());
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(1, consumed(m, RAW_SHARK));
	}
}
