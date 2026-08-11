package com.skillinginfo.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bounded pending-ledger for confirmed ground pickups (SPEC.md §20a),
 * structurally identical to {@link DropCorrelator} - a "Take" click doesn't
 * guarantee the pickup succeeded (contested tile, full inventory), so it's
 * held pending until a matching inventory increase confirms it, with a
 * timeout so a stale click can't be misattributed to a later pickup.
 * <p>
 * Phase 3 correlates the click against the resulting inventory increase
 * only - §20a's full model (also confirming the item's ground quantity
 * decreased at that tile) is deferred, the same simplification Phase 2
 * made for drops in §21.
 */
public class PickupCorrelator
{
	private static final int TIMEOUT_TICKS = 5;

	private static final class PendingPickup
	{
		final int itemId;
		final int clickTick;

		PendingPickup(int itemId, int clickTick)
		{
			this.itemId = itemId;
			this.clickTick = clickTick;
		}
	}

	private final Deque<PendingPickup> pending = new ArrayDeque<>();

	public void onTakeClicked(int itemId, int tick)
	{
		pending.addLast(new PendingPickup(itemId, tick));
	}

	/**
	 * @return confirmed pickup quantities for this tick, keyed by itemId.
	 */
	public Map<Integer, Integer> resolve(int currentTick, Map<Integer, Integer> increasedThisTick)
	{
		while (!pending.isEmpty() && currentTick - pending.peekFirst().clickTick > TIMEOUT_TICKS)
		{
			pending.pollFirst();
		}

		Map<Integer, Integer> confirmed = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : increasedThisTick.entrySet())
		{
			Iterator<PendingPickup> it = pending.iterator();
			while (it.hasNext())
			{
				PendingPickup pendingPickup = it.next();
				if (pendingPickup.itemId == entry.getKey())
				{
					confirmed.merge(entry.getKey(), entry.getValue(), Integer::sum);
					it.remove();
					break;
				}
			}
		}
		return confirmed;
	}

	public void reset()
	{
		pending.clear();
	}
}
