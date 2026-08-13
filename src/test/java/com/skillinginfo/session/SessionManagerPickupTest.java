package com.skillinginfo.session;

import net.runelite.api.TileItem;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static com.skillinginfo.session.SessionManagerHarness.STARTED_TICK;
import static com.skillinginfo.session.SessionManagerHarness.entry;
import static com.skillinginfo.session.SessionManagerHarness.items;
import static com.skillinginfo.session.SessionManagerHarness.startedSession;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §20a: a ground pickup is confirmed by the item leaving the ground, not by
 * the player having asked for it.
 * <p>
 * The distinction matters because a "Take" click is a request, and requests
 * fail - the tile is contested, the inventory is full, the walk is
 * interrupted. A click that never resolves stays pending, and until `[v9]`
 * would then claim the next inventory increase of that item from any source
 * at all.
 */
public class SessionManagerPickupTest
{
	private static final int LOGS = ItemID.LOGS;
	private static final WorldPoint TILE = new WorldPoint(3200, 3200, 0);

	/** The tracker only ever reads id and quantity off a TileItem. */
	private static TileItem groundItem(int itemId, int quantity)
	{
		TileItem item = mock(TileItem.class);
		when(item.getId()).thenReturn(itemId);
		when(item.getQuantity()).thenReturn(quantity);
		return item;
	}

	@Test
	public void aStaleTakeClickDoesNotStealTheNextThingYouProduce()
	{
		// The click is 18 seconds' worth of pending, and matching on item id
		// alone meant the next log the player *chopped* was booked as a
		// ground pickup. That moved the quantity out of `generated` and into
		// `pickedUp`, and since net retained counts pickups while the
		// retention denominator doesn't, the panel rendered RETAINED 125.0%.
		SessionManager m = startedSession(Skill.WOODCUTTING);
		int xp = 300;
		int tick = STARTED_TICK;
		for (int i = 1; i <= 4; i++)
		{
			tick += 2;
			xp += 100;
			m.onStatChanged(Skill.WOODCUTTING, xp);
			m.onInventoryChanged(items(LOGS, i));
			m.onGameTick(tick);
		}
		assertEquals(4, entry(m, LOGS).getGenerated());

		m.onTakeClicked(LOGS); // ground logs; the pickup never happens
		tick += 10;            // still inside the 30-tick pending window
		xp += 100;
		m.onStatChanged(Skill.WOODCUTTING, xp);
		m.onInventoryChanged(items(LOGS, 5)); // fifth log CHOPPED, not taken
		m.onGameTick(tick);

		ItemFlowEntry logs = entry(m, LOGS);
		assertEquals("nothing left the ground, so nothing was picked up", 0, logs.getPickedUp());
		assertEquals("the fifth log was cut, like the other four", 5, logs.getGenerated());
		assertEquals("and retention stays inside its own range",
			1.0, m.getCurrentSession().getRetentionRate(), 0.0001);
	}

	@Test
	public void aRealPickupIsStillConfirmed()
	{
		// The control that stops the rule above being satisfied by simply
		// never confirming anything: click, the item leaves the ground, the
		// inventory gains it.
		SessionManager m = startedSession(Skill.WOODCUTTING);

		m.onGroundItemSpawned(TILE, groundItem(LOGS, 1));
		m.onTakeClicked(LOGS);
		m.onGroundItemDespawned(TILE, groundItem(LOGS, 1));
		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(1, entry(m, LOGS).getPickedUp());
		assertEquals("a pickup is not the activity's own output", 0, entry(m, LOGS).getGenerated());
	}

	@Test
	public void aPartialPickupIsConfirmedOnlyAsFarAsTheGroundAccountsForIt()
	{
		// Two logs leave a stack of five while the player is also chopping.
		// The ground evidence caps the pickup; the rest is output.
		SessionManager m = startedSession(Skill.WOODCUTTING);

		m.onGroundItemSpawned(TILE, groundItem(LOGS, 5));
		m.onTakeClicked(LOGS);
		m.onGroundItemQuantityChanged(TILE, groundItem(LOGS, 3), 5, 3);
		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items(LOGS, 3)); // 2 taken, 1 chopped
		m.onGameTick(STARTED_TICK + 1);

		ItemFlowEntry logs = entry(m, LOGS);
		assertEquals(2, logs.getPickedUp());
		assertEquals(1, logs.getGenerated());
	}

	@Test
	public void groundStateIsDroppedWhenTheSceneGoesAway()
	{
		// The tracker had no removal path except despawn events, which are
		// not guaranteed for an unloaded region - RuneLite core clears its
		// own ground state on WorldViewUnloaded rather than relying on them.
		SessionManager m = startedSession(Skill.WOODCUTTING);

		m.onGroundItemSpawned(TILE, groundItem(LOGS, 1));
		m.onGroundItemDespawned(TILE, groundItem(LOGS, 1));
		m.onWorldViewUnloaded();

		// the despawn evidence went with it, so a click can't cash it in
		m.onTakeClicked(LOGS);
		m.onStatChanged(Skill.WOODCUTTING, 400);
		m.onInventoryChanged(items(LOGS, 1));
		m.onGameTick(STARTED_TICK + 1);

		assertEquals(0, entry(m, LOGS).getPickedUp());
	}
}
