package com.skillinginfo.session;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Item;

/**
 * Diffs successive inventory snapshots into per-item increase/decrease
 * deltas (SPEC.md §48). Deltas accumulate until consumed rather than being
 * tied to a specific tick, so callers don't need to run in lockstep with
 * whichever tick the underlying {@code ItemContainerChanged} fired on.
 */
public class InventoryDeltaTracker
{
	private final Map<Integer, Integer> lastSnapshot = new HashMap<>();
	private final Map<Integer, Integer> increased = new HashMap<>();
	private final Map<Integer, Integer> decreased = new HashMap<>();

	/**
	 * `[v9]` When set, the next snapshot only establishes the baseline and
	 * emits no deltas - the same guard {@link BankCorrelator} has always had.
	 * <p>
	 * Set at construction for containers RuneLite may not surface until the
	 * player opens them: a looting bag or seed box first seen mid-session
	 * would otherwise read as the whole bag arriving at once. The inventory
	 * and worn containers don't need it - they are populated at login, long
	 * before any session, so their first diff is drained while IDLE.
	 * <p>
	 * Set again by {@link #resetForNewAccount()}, where every container has
	 * the problem: a different account's inventory has nothing to do with the
	 * last one's.
	 */
	private boolean pendingBaseline;

	public InventoryDeltaTracker()
	{
		this(false);
	}

	public InventoryDeltaTracker(boolean baselineFirstSnapshot)
	{
		this.pendingBaseline = baselineFirstSnapshot;
	}

	public void onInventoryChanged(Item[] items)
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

		if (!pendingBaseline)
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

		pendingBaseline = false;
		lastSnapshot.clear();
		lastSnapshot.putAll(current);
	}

	public Map<Integer, Integer> consumeIncreased()
	{
		Map<Integer, Integer> result = new HashMap<>(increased);
		increased.clear();
		return result;
	}

	public Map<Integer, Integer> consumeDecreased()
	{
		Map<Integer, Integer> result = new HashMap<>(decreased);
		decreased.clear();
		return result;
	}

	/**
	 * Clears pending un-consumed deltas only. Deliberately does NOT clear
	 * {@code lastSnapshot} - that's the diffing baseline, not per-session
	 * state. Wiping it here would make the player's entire current
	 * inventory look newly generated on the next change after every
	 * session start, not just once at plugin startup.
	 */
	public void reset()
	{
		increased.clear();
		decreased.clear();
	}

	/**
	 * `[v9]` Drops the diffing baseline as well, for when the containers
	 * being diffed now belong to a *different account*. Unlike
	 * {@link #reset()}, keeping the old snapshot here would be actively
	 * wrong - it describes someone else's inventory.
	 * <p>
	 * The next snapshot is treated as a baseline rather than as an arrival,
	 * so this doesn't reintroduce the v6 bug in a new place.
	 */
	public void resetForNewAccount()
	{
		reset();
		lastSnapshot.clear();
		pendingBaseline = true;
	}
}
