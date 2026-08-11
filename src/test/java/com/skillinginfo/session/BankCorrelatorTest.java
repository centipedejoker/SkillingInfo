package com.skillinginfo.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Item;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers SPEC.md §55's banking test list. The three-way minimum in
 * {@link BankCorrelator#resolve} is the spec's most safety-critical
 * mechanism - it's what guarantees §27's "prefer undercounting over false
 * attribution" instead of merely intending it - and it's pure logic, so it
 * gets real tests rather than only live play-testing.
 */
public class BankCorrelatorTest
{
	private static final int LOGS = 1521;
	private static final int RUNE = 565;

	private static Item[] bank(int... idQtyPairs)
	{
		Item[] items = new Item[idQtyPairs.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idQtyPairs[i * 2], idQtyPairs[i * 2 + 1]);
		}
		return items;
	}

	private static Map<Integer, Integer> decreases(int itemId, int qty)
	{
		Map<Integer, Integer> m = new HashMap<>();
		m.put(itemId, qty);
		return m;
	}

	/** Session holds `qty` of `itemId` outstanding, nothing else. */
	private static IntUnaryOperator outstanding(int itemId, int qty)
	{
		return id -> id == itemId ? qty : 0;
	}

	@Test
	public void firstBankOpenEstablishesBaselineWithoutAttributing()
	{
		BankCorrelator correlator = new BankCorrelator();

		// player opens bank for the first time with a big existing stack
		correlator.onBankChanged(bank(LOGS, 10_000));

		Map<Integer, Integer> banked = correlator.resolve(decreases(LOGS, 10_000), outstanding(LOGS, 10_000));
		assertTrue("first bank observation must not be read as a deposit", banked.isEmpty());
	}

	@Test
	public void directDepositIsConfirmed()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 100));

		correlator.onBankChanged(bank(LOGS, 128));
		Map<Integer, Integer> banked = correlator.resolve(decreases(LOGS, 28), outstanding(LOGS, 28));

		assertEquals(Integer.valueOf(28), banked.get(LOGS));
	}

	@Test
	public void depositIsCappedByWhatTheSessionActuallyHolds()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 100));

		// player deposits 28 logs but only 5 of them were gathered this
		// session - the other 23 were already carried
		correlator.onBankChanged(bank(LOGS, 128));
		Map<Integer, Integer> banked = correlator.resolve(decreases(LOGS, 28), outstanding(LOGS, 5));

		assertEquals("capped at session-outstanding, not the full deposit", Integer.valueOf(5), banked.get(LOGS));
	}

	@Test
	public void bankIncreaseWithoutMatchingInventoryDecreaseIsNotAttributed()
	{
		// SPEC.md §27's worked example: unrelated bank movement while a
		// session happens to be running must never be credited
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(RUNE, 500));

		correlator.onBankChanged(bank(RUNE, 1200));
		Map<Integer, Integer> banked = correlator.resolve(Collections.emptyMap(), outstanding(RUNE, 999));

		assertTrue("no inventory decrease means no confirmed deposit", banked.isEmpty());
	}

	@Test
	public void withdrawalIsInert()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 100));

		// bank goes down, inventory goes up - the deposit signature requires
		// the opposite, so nothing should resolve
		correlator.onBankChanged(bank(LOGS, 60));
		Map<Integer, Integer> banked = correlator.resolve(Collections.emptyMap(), outstanding(LOGS, 40));

		assertTrue(banked.isEmpty());
	}

	@Test
	public void withdrawThenRedepositOfPreexistingStockIsNotCredited()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 100));

		// withdraw 40 (bank 100 -> 60), nothing resolves
		correlator.onBankChanged(bank(LOGS, 60));
		assertTrue(correlator.resolve(Collections.emptyMap(), outstanding(LOGS, 0)).isEmpty());

		// put them straight back (bank 60 -> 100). The inventory decrease is
		// real, but the session never acquired these, so outstanding is 0.
		correlator.onBankChanged(bank(LOGS, 100));
		Map<Integer, Integer> banked = correlator.resolve(decreases(LOGS, 40), outstanding(LOGS, 0));

		assertTrue("redepositing stock the session never acquired is not account gain", banked.isEmpty());
	}

	@Test
	public void depositAllCreditsEachItemIndependently()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 0, RUNE, 0));

		correlator.onBankChanged(bank(LOGS, 12, RUNE, 30));

		Map<Integer, Integer> invDecreases = new HashMap<>();
		invDecreases.put(LOGS, 12);
		invDecreases.put(RUNE, 30);
		// only the logs were gathered this session; the runes came from
		// somewhere else entirely
		Map<Integer, Integer> banked = correlator.resolve(invDecreases, id -> id == LOGS ? 12 : 0);

		assertEquals(Integer.valueOf(12), banked.get(LOGS));
		assertTrue("unrelated item in the same Deposit All is not credited", !banked.containsKey(RUNE));
	}

	@Test
	public void partialDepositsAcrossOneVisitAccumulateCorrectly()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 0));

		correlator.onBankChanged(bank(LOGS, 10));
		assertEquals(Integer.valueOf(10), correlator.resolve(decreases(LOGS, 10), outstanding(LOGS, 25)).get(LOGS));

		correlator.onBankChanged(bank(LOGS, 25));
		// 10 already banked, so 15 still outstanding
		assertEquals(Integer.valueOf(15), correlator.resolve(decreases(LOGS, 15), outstanding(LOGS, 15)).get(LOGS));
	}

	@Test
	public void unresolvedDeltasAreNotCarriedIntoALaterTick()
	{
		BankCorrelator correlator = new BankCorrelator();
		correlator.onBankChanged(bank(LOGS, 0));

		// bank increases with no matching inventory decrease - unattributable
		correlator.onBankChanged(bank(LOGS, 50));
		assertTrue(correlator.resolve(Collections.emptyMap(), outstanding(LOGS, 50)).isEmpty());

		// a later, unrelated inventory decrease must not retroactively claim it
		Map<Integer, Integer> banked = correlator.resolve(decreases(LOGS, 50), outstanding(LOGS, 50));
		assertTrue("stale bank delta must not be matched by a later decrease", banked.isEmpty());
	}
}
