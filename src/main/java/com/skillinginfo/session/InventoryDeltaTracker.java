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
	 * `[v9]` Whether the first snapshot establishes the baseline silently,
	 * as {@link BankCorrelator} has always done.
	 * <p>
	 * The inventory and worn containers are populated at login, long before
	 * any session, so their first diff is drained while IDLE and harmless.
	 * A looting bag or seed box is different: RuneLite may not surface its
	 * contents until the player opens it, which can happen mid-session and
	 * would then read as the whole bag arriving at once.
	 */
	private final boolean baselineFirstSnapshot;
	private boolean baselineEstablished;

	public InventoryDeltaTracker()
	{
		this(false);
	}

	public InventoryDeltaTracker(boolean baselineFirstSnapshot)
	{
		this.baselineFirstSnapshot = baselineFirstSnapshot;
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

		if (!baselineFirstSnapshot || baselineEstablished)
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
}
