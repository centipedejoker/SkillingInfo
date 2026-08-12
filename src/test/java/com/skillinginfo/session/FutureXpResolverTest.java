package com.skillinginfo.session;

import java.util.List;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks in SPEC.md §35's contract: an item with several legitimate uses
 * must never get a projection forced on it - it gets a *choice* instead,
 * and an item with no honest per-item value gets nothing at all.
 * "Returns empty" is a deliberate behavioural promise here, not an
 * oversight, so it's worth a test that fails loudly if someone later
 * "helpfully" fills the gaps in.
 */
public class FutureXpResolverTest
{
	@Test
	public void unambiguousItemHasExactlyOneUseAndNoChoice()
	{
		List<ItemUse> uses = FutureXpResolver.getUses(ItemID.RAW_SHARK);

		assertEquals(1, uses.size());
		assertEquals(Skill.COOKING, uses.get(0).skill);
		assertEquals(210.0, uses.get(0).xpPerItem, 0.001);
		assertFalse("raw fish has one use, so there's nothing to pick", FutureXpResolver.hasChoice(ItemID.RAW_SHARK));
	}

	@Test
	public void ambiguousItemOffersConcreteProductsNotSkills()
	{
		// §35's core case. The fix isn't "logs -> Fletching" (which has no
		// single value) but "logs -> Longbow (u)" (which is exactly 25).
		assertTrue(FutureXpResolver.hasChoice(ItemID.OAK_LOGS));
		List<ItemUse> uses = FutureXpResolver.getUses(ItemID.OAK_LOGS);

		assertEquals(3, uses.size());
		assertEquals("burning is the default", "BURN", uses.get(0).id);
		assertEquals(Skill.FIREMAKING, uses.get(0).skill);
		assertEquals(60.0, uses.get(0).xpPerItem, 0.001);

		ItemUse longbow = FutureXpResolver.findUse(ItemID.OAK_LOGS, "LONGBOW");
		assertNotNull(longbow);
		assertEquals(Skill.FLETCHING, longbow.skill);
		assertEquals(25.0, longbow.xpPerItem, 0.001);
	}

	@Test
	public void gildedAltarScalesTheBuryValue()
	{
		ItemUse bury = FutureXpResolver.findUse(ItemID.DRAGON_BONES, "BURY");
		ItemUse altar = FutureXpResolver.findUse(ItemID.DRAGON_BONES, "GILDED_ALTAR");

		assertEquals(72.0, bury.xpPerItem, 0.001);
		assertEquals(72.0 * 3.5, altar.xpPerItem, 0.001);
		assertEquals(Skill.PRAYER, altar.skill);
	}

	@Test
	public void offIsAlwaysSelectableForCataloguedItems()
	{
		List<ItemUse> selectable = FutureXpResolver.getSelectableUses(ItemID.OAK_LOGS);
		assertTrue(selectable.contains(ItemUse.OFF));
		assertNull("Off must carry no skill, so it projects nothing", ItemUse.OFF.skill);
	}

	@Test
	public void unmappedItemIsNotGuessed()
	{
		// §35: an item only gets a projection when some product gives it a
		// definite per-item value. A herb needs a secondary before it's worth
		// anything, clay's uses don't converge on one figure, and coal is an
		// ingredient rather than a product - none of them should be invented.
		//
		// Ores were previously in this list on the grounds that they'd need a
		// made-up coal ratio. They don't: the smelt XP is credited once
		// against the primary ore, never against the coal it consumes, so the
		// value is exact and the same bar can't be counted three times.
		assertTrue("herb must not be projected to Herblore", FutureXpResolver.getUses(ItemID.UNIDENTIFIED_GUAM).isEmpty());
		assertTrue("clay has no single honest value", FutureXpResolver.getUses(ItemID.CLAY).isEmpty());
		assertTrue("coal is a secondary, not a product", FutureXpResolver.getUses(ItemID.COAL).isEmpty());
		assertTrue(FutureXpResolver.getSelectableUses(ItemID.CLAY).isEmpty());
		assertNull(FutureXpResolver.getDefaultUse(ItemID.CLAY));
	}

	@Test
	public void notedItemsDoNotResolve()
	{
		// documented limitation: only unnoted ids are catalogued
		assertTrue(FutureXpResolver.getUses(ItemID.Cert.OAK_LOGS).isEmpty());
	}

	@Test
	public void unknownStoredUseIdDoesNotResolve()
	{
		// guards ItemUseStore's fallback path: a use id persisted by an
		// older version that no longer exists must not blow up or match
		assertNull(FutureXpResolver.findUse(ItemID.OAK_LOGS, "SOME_REMOVED_ACTIVITY"));
	}
}
