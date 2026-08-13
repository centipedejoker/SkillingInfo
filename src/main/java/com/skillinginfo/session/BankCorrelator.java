package com.skillinginfo.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import lombok.Getter;
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
	 * `[v9]` What one tick's bank increases account for, split two ways.
	 * <p>
	 * The distinction matters because <em>explaining</em> a movement and
	 * <em>crediting</em> it are different questions, and conflating them was
	 * a bug: depositing stock the session never acquired is capped to zero by
	 * step 4's three-way minimum, correctly - but the inventory decrease was
	 * then left in the pool and the consumption catch-all ate it. A deposit
	 * of items you already had was recorded as having used them up.
	 */
	public static final class Resolution
	{
		/**
		 * Inventory decreases accounted for by a deposit, whether or not the
		 * session may claim them. Claimed out of the decrease pool so no
		 * later rule can reinterpret them.
		 */
		@Getter
		private final Map<Integer, Integer> explained = new HashMap<>();

		/** §25a step 5: the attributable subset, capped at what the session holds. */
		@Getter
		private final Map<Integer, Integer> credited = new HashMap<>();
	}

	/**
	 * SPEC.md §25a steps 3-6.
	 *
	 * @param invDecreasedThisTick inventory decreases for this tick, already
	 * net of anything claimed by a more specific correlator (drops).
	 * @param outstandingLookup itemId → how much of that item the session
	 * acquired and still holds unbanked.
	 * @param unnotedId itemId → the id it is a note of, or -1. A bank stores
	 * items unnoted, so a deposit of notes lands under a different key on the
	 * two sides of the movement (§18 `[v9]`).
	 */
	public Resolution resolve(Map<Integer, Integer> invDecreasedThisTick,
		IntUnaryOperator outstandingLookup, IntUnaryOperator unnotedId)
	{
		Resolution resolution = new Resolution();
		if (increased.isEmpty())
		{
			return resolution;
		}

		for (Map.Entry<Integer, Integer> entry : increased.entrySet())
		{
			int bankItemId = entry.getKey();
			int bankDelta = entry.getValue();

			// The inventory may have parted with this item under either id:
			// the bank's own (unnoted), or the note of it.
			for (int inventoryItemId : inventoryFormsOf(bankItemId, invDecreasedThisTick, unnotedId))
			{
				if (bankDelta <= 0)
				{
					break;
				}

				int invDelta = invDecreasedThisTick.getOrDefault(inventoryItemId, 0);
				int matched = Math.min(bankDelta, invDelta);
				if (matched <= 0)
				{
					continue;
				}
				bankDelta -= matched;
				resolution.explained.merge(inventoryItemId, matched, Integer::sum);

				// §25a step 4: the three-way minimum. Capping here rather than
				// above is the point - the movement is real either way, only
				// the claim to it is limited.
				int creditable = Math.min(matched, outstandingLookup.applyAsInt(inventoryItemId));
				if (creditable > 0)
				{
					resolution.credited.merge(inventoryItemId, creditable, Integer::sum);
				}
			}
		}

		// §25a step 5: leftover bank increases are dropped on the floor, not
		// carried forward - an unattributed deposit is unattributable, and
		// keeping it around would only give a later tick a chance to
		// misattribute it.
		increased.clear();
		return resolution;
	}

	/**
	 * The inventory-side ids a bank increase of {@code bankItemId} could have
	 * come from: the id itself first, then any note of it.
	 */
	private static List<Integer> inventoryFormsOf(int bankItemId,
		Map<Integer, Integer> invDecreasedThisTick, IntUnaryOperator unnotedId)
	{
		List<Integer> forms = new ArrayList<>(2);
		forms.add(bankItemId);
		for (Integer candidate : invDecreasedThisTick.keySet())
		{
			if (candidate != bankItemId && unnotedId.applyAsInt(candidate) == bankItemId)
			{
				forms.add(candidate);
			}
		}
		return forms;
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
	 * Drops pending bank increases without attributing them, for ticks where
	 * {@link #resolve} won't run - no session, or one that isn't recording.
	 * Same reasoning as step 5 inside {@code resolve}: an unattributable
	 * deposit is dropped on the floor rather than carried, because carrying
	 * it only gives a later tick the chance to misattribute it.
	 */
	public void discardPending()
	{
		increased.clear();
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
