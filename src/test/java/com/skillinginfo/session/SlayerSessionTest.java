package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers SPEC.md §37's Slayer model, and in particular the distinction the
 * plugin exists to make: loot that <em>dropped</em> is not loot you
 * <em>have</em>.
 */
public class SlayerSessionTest
{
	private static ActivitySession combatSession()
	{
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.SLAYER); // §7a: combat's group key
		return s;
	}

	@Test
	public void droppedLootIsNotAcquiredUntilItIsPickedUp()
	{
		// the whole premise: 10 rune items dropped, 6 taken
		ActivitySession s = combatSession();
		s.addGeneratedOnly(ItemID.DEATHRUNE, 10);

		assertEquals("it dropped", 10, s.getTotalGenerated());
		assertEquals("but it's on the floor, not in the account", 0, s.getTotalNetRetained());

		s.addPickedUp(ItemID.DEATHRUNE, 6);
		assertEquals(10, s.getTotalGenerated());
		assertEquals(6, s.getTotalNetRetained());
	}

	@Test
	public void theRatioIsPickupRateForCombat()
	{
		// §31: same arithmetic as retention, different question - what share
		// of what dropped did the player actually take
		ActivitySession s = combatSession();
		s.addGeneratedOnly(ItemID.DEATHRUNE, 10);
		s.addPickedUp(ItemID.DEATHRUNE, 6);

		assertEquals(0.6, s.getRetentionRate(), 0.001);
	}

	@Test
	public void droppingLootAfterPickingItUpReducesTheGain()
	{
		// §57's worked example: 10 drop, 6 taken, 2 later dropped
		ActivitySession s = combatSession();
		s.addGeneratedOnly(ItemID.DEATHRUNE, 10);
		s.addPickedUp(ItemID.DEATHRUNE, 6);
		s.addDropped(ItemID.DEATHRUNE, 2);

		assertEquals(10, s.getTotalGenerated());
		assertEquals("headline account gain", 4, s.getTotalNetRetained());
	}

	@Test
	public void killsAndRateAreSessionScoped()
	{
		ActivitySession s = combatSession();
		s.recordKills(30);
		s.setActiveSeconds(600);

		assertEquals(30, s.getKills());
		assertEquals(180.0, s.getKillsPerHour(), 0.001);
	}

	@Test
	public void skillingOutputIsStillAcquiredOnGeneration()
	{
		// the combat change must not alter gathering, where output lands
		// straight in the inventory
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.WOODCUTTING);
		s.addGenerated(ItemID.OAK_LOGS, 40);

		assertEquals(40, s.getTotalGenerated());
		assertEquals(40, s.getTotalNetRetained());
	}

	@Test
	public void repeatedBankContainerUpdatesAreOneTrip()
	{
		// §39: the bank container fires on every deposit, and thirty
		// timestamps a second apart describe one visit, not thirty trips
		ActivitySession s = combatSession();
		java.time.Instant base = java.time.Instant.parse("2026-01-01T00:00:00Z");
		s.recordBankVisit(base);
		s.recordBankVisit(base.plusSeconds(1));
		s.recordBankVisit(base.plusSeconds(2));
		assertEquals(1, s.getTripBoundaries().size());

		// a genuinely separate trip, much later
		s.recordBankVisit(base.plusSeconds(600));
		assertEquals(2, s.getTripBoundaries().size());
	}

	@Test
	public void combatSessionsAreCategorisedSeparately()
	{
		assertEquals("COMBAT", combatSession().getCategory());
		assertTrue(TrackingGroups.isCombatGroup(Skill.SLAYER));
	}
}
