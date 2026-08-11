package com.skillinginfo.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bounded pending-ledger for confirmed drops (SPEC.md §21), mirroring the
 * ground-pickup pattern in §20a: a menu click doesn't guarantee the drop
 * actually happened, so it's held as pending and only confirmed once a
 * matching inventory decrease shows up, with a timeout so a stale click
 * can never be misattributed to a later, unrelated decrease.
 * <p>
 * Phase 2 correlates the click against the resulting inventory decrease
 * only - §21's full model (also confirming the item appears on the ground)
 * is added once {@code GroundItemTracker} exists in Phase 3.
 */
public class DropCorrelator
{
	private static final int TIMEOUT_TICKS = 5;

	private static final class PendingDrop
	{
		final int itemId;
		final int clickTick;

		PendingDrop(int itemId, int clickTick)
		{
			this.itemId = itemId;
			this.clickTick = clickTick;
		}
	}

	private final Deque<PendingDrop> pending = new ArrayDeque<>();

	public void onDropClicked(int itemId, int tick)
	{
		pending.addLast(new PendingDrop(itemId, tick));
	}

	/**
	 * @return confirmed drop quantities for this tick, keyed by itemId.
	 */
	public Map<Integer, Integer> resolve(int currentTick, Map<Integer, Integer> decreasedThisTick)
	{
		while (!pending.isEmpty() && currentTick - pending.peekFirst().clickTick > TIMEOUT_TICKS)
		{
			pending.pollFirst();
		}

		Map<Integer, Integer> confirmed = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : decreasedThisTick.entrySet())
		{
			Iterator<PendingDrop> it = pending.iterator();
			while (it.hasNext())
			{
				PendingDrop pendingDrop = it.next();
				if (pendingDrop.itemId == entry.getKey())
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
