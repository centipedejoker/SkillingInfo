package com.skillinginfo.session;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Item;

/**
 * Confirmed-banked correlation (SPEC.md §25a) and the single owner of the
 * running bank-contents snapshot (§26 - there is deliberately only one
 * bank-state tracker in the system, not two).
 * <p>
 * The core safeguard is the three-way minimum in {@link #resolve}: a bank
 * increase is only attributed to the session when it's backed by a
 * same-tick inventory decrease AND the session actually holds that much
 * outstanding. Anything left over is silently unattributed, which is what
 * makes §27's worked example (unrelated Death rune/Coal bank increases
 * never credited) a guaranteed property of the algorithm rather than an
 * intention.
 */
public class BankCorrelator
{
	private final Map<Integer, Integer> lastSnapshot = new HashMap<>();
	private final Map<Integer, Integer> increased = new HashMap<>();
	private final Map<Integer, Integer> decreased = new HashMap<>();

	/**
	 * The first bank container we ever see establishes the diffing baseline
	 * without emitting deltas - otherwise the player's entire existing bank
	 * would look like one enormous deposit the moment they first open it.
	 * (The three-way min in {@link #resolve} would also independently
	 * reject that, since there'd be no matching inventory decrease, but
	 * relying on a second-order safeguard for a first-order mistake is how
	 * the equivalent InventoryDeltaTracker bug happened.)
	 */
	private boolean baselineEstablished;

	public void onBankChanged(Item[] items)
	{
		Map<Integer, Integer> current = new HashMap<>();
		for (Item item : items)
		{
			if (item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			current.merge(item.getId(), item.getQuantity(), Integer::sum);
		}

		if (baselineEstablished)
		{
			for (Map.Entry<Integer, Integer> entry : current.entrySet())
			{
				int delta = entry.getValue() - lastSnapshot.getOrDefault(entry.getKey(), 0);
				if (delta > 0)
				{
					increased.merge(entry.getKey(), delta, Integer::sum);
				}
			}
			for (Map.Entry<Integer, Integer> entry : lastSnapshot.entrySet())
			{
				int delta = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
				if (delta > 0)
				{
					decreased.merge(entry.getKey(), delta, Integer::sum);
				}
			}
		}

		baselineEstablished = true;
		lastSnapshot.clear();
		lastSnapshot.putAll(current);
	}

	/**
	 * SPEC.md §25a steps 3-6.
	 *
	 * @param invDecreasedThisTick inventory decreases for this tick, already
	 * net of anything claimed by a more specific correlator (drops).
	 * @param outstandingLookup itemId → how much of that item the session
	 * acquired and still holds unbanked.
	 * @return confirmed banked quantities, keyed by itemId.
	 */
	public Map<Integer, Integer> resolve(Map<Integer, Integer> invDecreasedThisTick, IntUnaryOperator outstandingLookup)
	{
		Map<Integer, Integer> confirmed = new HashMap<>();
		if (increased.isEmpty())
		{
			return confirmed;
		}

		for (Map.Entry<Integer, Integer> entry : increased.entrySet())
		{
			int itemId = entry.getKey();
			int bankDelta = entry.getValue();
			int invDelta = invDecreasedThisTick.getOrDefault(itemId, 0);
			int outstanding = outstandingLookup.applyAsInt(itemId);

			int candidateBanked = Math.min(bankDelta, Math.min(invDelta, outstanding));
			if (candidateBanked > 0)
			{
				confirmed.put(itemId, candidateBanked);
			}
		}

		// §25a step 5: leftover bank increases are dropped on the floor, not
		// carried forward - an unattributed deposit is unattributable, and
		// keeping it around would only give a later tick a chance to
		// misattribute it.
		increased.clear();
		return confirmed;
	}

	/**
	 * Bank <em>decreases</em> - withdrawals. Not a correlation signal in their
	 * own right: their job is to explain away the matching inventory increase
	 * so it isn't mistaken for skilling output (§16). Withdrawing 27 raw fish
	 * at a bank while Cooking XP is still inside the generation window
	 * otherwise reads as having produced 27 fish.
	 * <p>
	 * The deposit side has always been protected by the three-way minimum in
	 * {@link #resolve}; this is its missing mirror.
	 */
	public Map<Integer, Integer> consumeDecreased()
	{
		Map<Integer, Integer> result = new HashMap<>(decreased);
		decreased.clear();
		return result;
	}

	/**
	 * Clears pending un-consumed deltas only. Deliberately does NOT clear
	 * {@code lastSnapshot} or {@code baselineEstablished} - that's the
	 * diffing baseline, not per-session state (same reasoning as
	 * {@link InventoryDeltaTracker#reset()}).
	 */
	public void reset()
	{
		increased.clear();
		decreased.clear();
	}
}
