package com.skillinginfo.session;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Multi-skill session support (SPEC.md §7a). Every combat hit awards
 * Hitpoints XP alongside the combat-style XP, and a Slayer task adds Slayer
 * XP on top - that simultaneous gain is normal for one activity, not the
 * multi-skill reward burst §9 is meant to reject. A tracking group is a
 * fixed set of skills whose simultaneous XP gain counts as one activity;
 * every other skill is its own singleton group.
 * <p>
 * {@link #groupKey} collapses a skill to its group's canonical identity -
 * the value everything else (candidate buffering, suppression, session
 * identity, the headline rate) keys on instead of the raw skill.
 */
public final class TrackingGroups
{
	public static final Set<Skill> COMBAT_GROUP = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS, Skill.SLAYER);

	private TrackingGroups()
	{
	}

	public static Skill groupKey(Skill skill)
	{
		return COMBAT_GROUP.contains(skill) ? Skill.SLAYER : skill;
	}

	public static boolean isCombatGroup(Skill groupKey)
	{
		return groupKey == Skill.SLAYER;
	}
}
