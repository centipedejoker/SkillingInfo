package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static com.skillinginfo.session.SessionManagerHarness.STARTED_TICK;
import static com.skillinginfo.session.SessionManagerHarness.entry;
import static com.skillinginfo.session.SessionManagerHarness.items;
import static com.skillinginfo.session.SessionManagerHarness.startedSession;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
	// gameval.ItemID has no constants for noted forms; 384 is what
	// getLinkedNoteId() pairs with 383
	private static final int RAW_SHARK_NOTED = 384;
	private static final int COAL = ItemID.COAL;
	private static final int LOOTING_BAG = InventoryID.LOOTING_BAG;
	private static final int COINS = ItemID.COINS;

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
	public void aNotedWithdrawalIsNotSkillingOutput()
	{
		// A bank stores items unnoted; withdrawing "as note" puts a different
		// id in the inventory, so the two halves of one movement don't share
		// a key. §18 `[v8]` fixed the unnoted path and left this one live.
		SessionManager m = startedSession(Skill.COOKING, items(), items(), items(RAW_SHARK, 27));

		m.onStatChanged(Skill.COOKING, 400);
		m.onBankChanged(items());
		m.onInventoryChanged(items(RAW_SHARK_NOTED, 27));
		m.onGameTick(STARTED_TICK + 1);

		assertNull("a withdrawal is not output, noted or otherwise", entry(m, RAW_SHARK_NOTED));
		assertNull("and nothing lands against the unnoted id either", entry(m, RAW_SHARK));
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
		//
		// The item pairing is deliberately synthetic - the pools are keyed by
		// id and don't care which id it is, and this exercises the drain in
		// the fewest events. What is not arbitrary is the skill: §18 [v9]
		// only lets an activity consume when it has inputs, and Woodcutting
		// has none, so asserting a consumption there would be asserting the
		// nonsense that chopping uses up an axe.
		SessionManager m = startedSession(Skill.COOKING, items(AXE, 1), items(), items());

		// well past the XP window: equip the axe
		m.onInventoryChanged(items());
		m.onEquipmentChanged(items(AXE, 1));
		m.onGameTick(20);

		// a second axe arrives, still outside the window so it isn't output
		m.onInventoryChanged(items(AXE, 1));
		m.onGameTick(24);

		// XP resumes and that axe is genuinely used up
		m.onStatChanged(Skill.COOKING, 400);
		m.onInventoryChanged(items());
		m.onGameTick(25);

		assertEquals("the equip five ticks earlier explains nothing here", 1, consumed(m, AXE));
	}

	@Test
	public void depositingStockTheSessionNeverAcquiredIsNotConsumption()
	{
		// §25a step 4 correctly credits nothing here - these sharks were held
		// before the session began. The bug was what happened next: the
		// inventory decrease was left in the pool and the consumption
		// catch-all ate it, so a deposit was recorded as having used them up.
		// Explaining a movement and crediting it are separate questions.
		SessionManager m = startedSession(Skill.COOKING, items(RAW_SHARK_NOTED, 27), items(), items());

		m.onStatChanged(Skill.COOKING, 400);
		m.onBankChanged(items(RAW_SHARK, 27));
		m.onInventoryChanged(items());
		m.onGameTick(STARTED_TICK + 1);

		assertNull("a deposit is not consumption", entry(m, RAW_SHARK_NOTED));
		assertNull("and the unnoted id is the bank's, not the inventory's", entry(m, RAW_SHARK));
	}

	@Test
	public void anActivityWithNoInputsRecordsAnUnexplainedLossNotConsumption()
	{
		// Deposit box, bank chest, GE collection box, group storage: the
		// inventory decreases with no BANK container update at all. Chopping
		// cannot use up a log, so this is §50's unexplained loss - which,
		// unlike consumption, is never netted off account gain.
		SessionManager m = startedSession(Skill.WOODCUTTING, items(LOGS, 27), items(), items());

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items());
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("woodcutting has no inputs", 0, consumed(m, LOGS));
		assertEquals(27, entry(m, LOGS).getOtherLoss());
	}

	@Test
	public void stowingOutputInAContainerlessBagStillCountsAsRetained()
	{
		// The coal bag case that made a fully banked 27-coal trip report
		// `RETAINED 0.0% - 0 kept, 27 lost`.
		SessionManager m = startedSession(Skill.MINING);
		int xp = 300;
		int tick = STARTED_TICK;
		for (int i = 1; i <= 27; i++)
		{
			tick += 2; // mine a coal
			xp += 50;
			m.onStatChanged(Skill.MINING, xp);
			m.onInventoryChanged(items(COAL, 1));
			m.onGameTick(tick);

			tick += 1; // ...and stow it, still mining
			m.onInventoryChanged(items());
			m.onGameTick(tick);
		}

		ItemFlowEntry coal = entry(m, COAL);
		assertEquals(27, coal.getGenerated());
		assertEquals("mining consumes nothing", 0, coal.getConsumed());
		assertEquals(27, coal.getOtherLoss());
		assertEquals("the coal is in the bag, not destroyed", 27, coal.getNetRetained());
		assertEquals("a full trip reads as a full trip",
			1.0, m.getCurrentSession().getRetentionRate(), 0.0001);
	}

	@Test
	public void stowingInAContainerRuneliteExposesIsClaimedOutright()
	{
		// Where a real container exists there is no ambiguity to resolve -
		// it is claimed like the worn container, so nothing reaches either
		// catch-all and no unexplained loss is recorded at all.
		SessionManager m = startedSession(Skill.WOODCUTTING, items(LOGS, 5), items(), items());

		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items());
		m.onSideContainerChanged(LOOTING_BAG, items());          // baseline
		m.onSideContainerChanged(LOOTING_BAG, items(LOGS, 5));   // ...now holding them
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(0, consumed(m, LOGS));
		assertNull("a claimed transfer is explained outright, not bucketed", entry(m, LOGS));
	}

	@Test
	public void dyingIsNotTheActivityConsumingYourInventory()
	{
		// The generation catch-all is correctly disabled for combat; the
		// consumption one was not, and combat refreshes lastXpCreditTick on
		// every hit, so the window is permanently open during a task. Dying
		// carrying 500k coins recorded `Consumed -500,000` - permanently,
		// because gravestone retrieval is never re-credited: there is no Take
		// click, and generation is off for combat.
		SessionManager m = startedSession(Skill.SLAYER,
			items(RAW_SHARK, 20, COINS, 500_000), items(), items());

		m.onInventoryChanged(items()); // died
		m.onGameTick(STARTED_TICK + 1);

		assertEquals("a Slayer task does not eat 500k coins",
			0, m.getCurrentSession().getTotalConsumed());
		assertEquals(20, entry(m, RAW_SHARK).getOtherLoss());
		assertEquals(500_000, entry(m, COINS).getOtherLoss());
	}

	@Test
	public void aDeathEventSuppressesTheCatchAllsEvenWhenItemsAreKept()
	{
		// The bulk-loss heuristic needs the inventory to end up empty, so it
		// misses a death where items were protected. ActorDeath doesn't.
		SessionManager m = startedSession(Skill.SLAYER,
			items(RAW_SHARK, 20, COINS, 500_000), items(), items());

		m.onLocalPlayerDeath();
		m.onInventoryChanged(items(COINS, 500_000)); // coins protected
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(0, m.getCurrentSession().getTotalConsumed());
		assertEquals(20, entry(m, RAW_SHARK).getOtherLoss());
	}

	@Test
	public void eatingYourLastSharkIsStillConsumption()
	{
		// the control the bulk-loss rule must not swallow: one stack, and an
		// inventory that happens to end up empty
		SessionManager m = startedSession(Skill.SLAYER, items(RAW_SHARK, 1), items(), items());

		m.onInventoryChanged(items());
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(1, consumed(m, RAW_SHARK));
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
