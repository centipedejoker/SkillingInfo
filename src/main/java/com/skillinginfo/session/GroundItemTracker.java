package com.skillinginfo.session;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/**
 * Live ground-item state per tile (SPEC.md §48), fed by
 * {@code ItemSpawned}/{@code ItemDespawned}/{@code ItemQuantityChanged}.
 * Phase 3's {@link PickupCorrelator} doesn't consume this yet - it
 * correlates on inventory evidence alone, the same simplification Phase 2
 * made for drops (§21). This exists now because §20b's ownership-based
 * attribution confidence (Phase 6, once combat/Slayer loot needs it) reads
 * straight off {@link TileItem#getOwnership()}, and building the tracker
 * against real events now is cheaper than retrofitting it later.
 * <p>
 * Known limitation: keyed by (tile, itemId), so two players' distinct
 * private drops of the same item on the same tile collapse to one entry.
 * Acceptable for now - §20b already prefers session-aggregate attribution
 * over false per-source precision in exactly this situation.
 */
public class GroundItemTracker
{
	private final Map<WorldPoint, Map<Integer, TileItem>> tiles = new HashMap<>();

	public void onItemSpawned(WorldPoint point, TileItem item)
	{
		tiles.computeIfAbsent(point, p -> new HashMap<>()).put(item.getId(), item);
	}

	public void onItemQuantityChanged(WorldPoint point, TileItem item)
	{
		onItemSpawned(point, item);
	}

	public void onItemDespawned(WorldPoint point, TileItem item)
	{
		Map<Integer, TileItem> atTile = tiles.get(point);
		if (atTile == null)
		{
			return;
		}
		atTile.remove(item.getId());
		if (atTile.isEmpty())
		{
			tiles.remove(point);
		}
	}

	public TileItem get(WorldPoint point, int itemId)
	{
		Map<Integer, TileItem> atTile = tiles.get(point);
		return atTile == null ? null : atTile.get(itemId);
	}
}
