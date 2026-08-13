package com.skillinginfo.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/**
 * Live ground-item state per tile (SPEC.md §48), fed by
 * {@code ItemSpawned}/{@code ItemDespawned}/{@code ItemQuantityChanged}.
 * <p>
 * `[v9]` This now does the job §20a always specified: it records what
 * actually <em>left</em> the ground, so {@link PickupCorrelator} can confirm
 * a pickup against evidence rather than against a menu click alone. Until
 * v9 nothing read this class at all - {@code get()} had no callers anywhere
 * in the source, so it was fed forever and never consulted, and never
 * cleared either.
 * <p>
 * Known limitation, unchanged: keyed by (tile, itemId), so two players'
 * distinct private drops of the same item on the same tile collapse to one
 * entry. Acceptable per §20b, which already prefers session-aggregate
 * attribution over false per-source precision in exactly this situation.
 */
public class GroundItemTracker
{
	/**
	 * How long a disappearance stays usable as evidence. The despawn and the
	 * inventory increase are delivered in the same tick, but the increase is
	 * only resolved on the following one, so this needs slack - not much.
	 */
	private static final int EVIDENCE_WINDOW_TICKS = 4;

	private static final class Disappearance
	{
		final int itemId;
		final int tick;
		int quantity;

		Disappearance(int itemId, int quantity, int tick)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.tick = tick;
		}
	}

	private final Map<WorldPoint, Map<Integer, TileItem>> tiles = new HashMap<>();

	/** Recent ground losses, oldest first, consumed as pickups claim them. */
	private final Deque<Disappearance> recent = new ArrayDeque<>();

	public void onItemSpawned(WorldPoint point, TileItem item)
	{
		tiles.computeIfAbsent(point, p -> new HashMap<>()).put(item.getId(), item);
	}

	public void onItemQuantityChanged(WorldPoint point, TileItem item, int oldQuantity, int newQuantity, int tick)
	{
		onItemSpawned(point, item);
		if (newQuantity < oldQuantity)
		{
			recent.addLast(new Disappearance(item.getId(), oldQuantity - newQuantity, tick));
		}
	}

	public void onItemDespawned(WorldPoint point, TileItem item, int tick)
	{
		Map<Integer, TileItem> atTile = tiles.get(point);
		if (atTile != null)
		{
			atTile.remove(item.getId());
			if (atTile.isEmpty())
			{
				tiles.remove(point);
			}
		}
		recent.addLast(new Disappearance(item.getId(), item.getQuantity(), tick));
	}

	/**
	 * `[v9]` Consumes up to {@code qty} of recent ground losses of this item.
	 *
	 * @return how much could be accounted for - the cap on how much of an
	 * inventory increase may be called a pickup
	 */
	public int claimDisappeared(int itemId, int currentTick, int qty)
	{
		pruneOlderThan(currentTick - EVIDENCE_WINDOW_TICKS);

		int claimed = 0;
		Iterator<Disappearance> it = recent.iterator();
		while (it.hasNext() && claimed < qty)
		{
			Disappearance candidate = it.next();
			if (candidate.itemId != itemId)
			{
				continue;
			}
			int take = Math.min(qty - claimed, candidate.quantity);
			candidate.quantity -= take;
			claimed += take;
			if (candidate.quantity <= 0)
			{
				it.remove();
			}
		}
		return claimed;
	}

	private void pruneOlderThan(int cutoffTick)
	{
		while (!recent.isEmpty() && recent.peekFirst().tick < cutoffTick)
		{
			recent.pollFirst();
		}
	}

	public TileItem get(WorldPoint point, int itemId)
	{
		Map<Integer, TileItem> atTile = tiles.get(point);
		return atTile == null ? null : atTile.get(itemId);
	}

	/**
	 * `[v9]` Drops everything. Called when the scene goes away and when a
	 * session ends - the per-tile map used to have no removal path except
	 * despawn events, and RuneLite core does not rely on those either
	 * ({@code GroundItemsPlugin} clears by world view on
	 * {@code WorldViewUnloaded}), so a long session that moved around
	 * accumulated tiles without bound.
	 */
	public void clear()
	{
		tiles.clear();
		recent.clear();
	}
}
