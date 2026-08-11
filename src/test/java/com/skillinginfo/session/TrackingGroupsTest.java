package com.skillinginfo.session;

import java.util.EnumSet;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Covers SPEC.md §7a's tracking groups and §9 [v7]'s primary/byproduct
 * relationship. The byproduct cases matter because getting them wrong is
 * silent: an infernal-tool user simply could never start a session, with
 * no error to explain why.
 */
public class TrackingGroupsTest
{
	@Test
	public void combatSkillsCollapseToOneGroupKey()
	{
		assertEquals(Skill.SLAYER, TrackingGroups.groupKey(Skill.ATTACK));
		assertEquals(Skill.SLAYER, TrackingGroups.groupKey(Skill.HITPOINTS));
		assertEquals(Skill.SLAYER, TrackingGroups.groupKey(Skill.SLAYER));
	}

	@Test
	public void nonCombatSkillsAreTheirOwnGroup()
	{
		assertEquals(Skill.WOODCUTTING, TrackingGroups.groupKey(Skill.WOODCUTTING));
		assertEquals(Skill.FIREMAKING, TrackingGroups.groupKey(Skill.FIREMAKING));
	}

	@Test
	public void infernalToolPairsResolveToTheGatheringSkill()
	{
		// the whole point: these must not read as reward bursts
		assertEquals(Skill.WOODCUTTING,
			TrackingGroups.resolvePrimary(EnumSet.of(Skill.WOODCUTTING, Skill.FIREMAKING)));
		assertEquals(Skill.MINING,
			TrackingGroups.resolvePrimary(EnumSet.of(Skill.MINING, Skill.SMITHING)));
		assertEquals(Skill.FISHING,
			TrackingGroups.resolvePrimary(EnumSet.of(Skill.FISHING, Skill.COOKING)));
	}

	@Test
	public void combatByproductsResolveToCombat()
	{
		// bonecrusher, herbicide - and both at once
		assertEquals(Skill.SLAYER,
			TrackingGroups.resolvePrimary(EnumSet.of(Skill.SLAYER, Skill.PRAYER)));
		assertEquals(Skill.SLAYER,
			TrackingGroups.resolvePrimary(EnumSet.of(Skill.SLAYER, Skill.PRAYER, Skill.HERBLORE)));
	}

	@Test
	public void genuineRewardBurstsStillHaveNoPrimary()
	{
		// §9's original case must keep being rejected
		assertNull(TrackingGroups.resolvePrimary(
			EnumSet.of(Skill.MINING, Skill.SMITHING, Skill.CRAFTING)));
		assertNull(TrackingGroups.resolvePrimary(
			EnumSet.of(Skill.WOODCUTTING, Skill.FISHING)));
	}

	@Test
	public void relationshipIsDirectionalNotAGroup()
	{
		// Firemaking is a byproduct of Woodcutting, never the reverse - if
		// this became symmetrical, a genuine Firemaking session paired with
		// any stray Woodcutting XP would be misattributed to chopping
		assertNull(TrackingGroups.resolvePrimary(EnumSet.of(Skill.FIREMAKING, Skill.CRAFTING)));
		assertNull(TrackingGroups.resolvePrimary(EnumSet.of(Skill.COOKING, Skill.SMITHING)));
	}

	@Test
	public void singleSkillIsNeverAByproductCase()
	{
		assertNull(TrackingGroups.resolvePrimary(EnumSet.of(Skill.WOODCUTTING)));
		assertNull(TrackingGroups.resolvePrimary(EnumSet.noneOf(Skill.class)));
	}
}
