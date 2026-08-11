package com.skillinginfo.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
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

	/**
	 * Group key → skills that some equipment can award *incidentally*
	 * alongside it, in the same tick, for a single action (SPEC.md §9 [v7]).
	 * <p>
	 * Infernal tools are the motivating case: an infernal axe grants
	 * Woodcutting and Firemaking together on one chop, so every action
	 * looked like §9's multi-skill reward burst and candidate detection
	 * could never fire at all for anyone using one. Bonecrusher (Prayer
	 * during combat) and herbicide (Herblore during combat) hit the same
	 * trap.
	 * <p>
	 * Crucially this is <em>directional</em>, not a shared group like
	 * {@link #COMBAT_GROUP}. Firemaking, Smithing and Cooking are all
	 * independently trainable, so permanently grouping them with their
	 * primary would break genuine sessions in those skills. The
	 * relationship only says "Firemaking XP arriving in the same tick as
	 * Woodcutting XP is incidental to the chopping", never the reverse.
	 */
	private static final Map<Skill, Set<Skill>> BYPRODUCTS = new EnumMap<>(Skill.class);

	static
	{
		BYPRODUCTS.put(Skill.WOODCUTTING, EnumSet.of(Skill.FIREMAKING));   // infernal axe
		BYPRODUCTS.put(Skill.MINING, EnumSet.of(Skill.SMITHING));          // infernal pickaxe
		BYPRODUCTS.put(Skill.FISHING, EnumSet.of(Skill.COOKING));          // infernal harpoon
		// combat's group key is SLAYER (§7a); bonecrusher and herbicide
		BYPRODUCTS.put(Skill.SLAYER, EnumSet.of(Skill.PRAYER, Skill.HERBLORE));
	}

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

	/**
	 * @return the primary group key when {@code groupKeys} is one primary
	 * plus only its own known byproducts (so it's a single action, not
	 * §9's reward burst), otherwise null.
	 */
	public static Skill resolvePrimary(Set<Skill> groupKeys)
	{
		if (groupKeys.size() < 2)
		{
			return null;
		}

		for (Skill candidate : groupKeys)
		{
			Set<Skill> byproducts = BYPRODUCTS.get(candidate);
			if (byproducts == null)
			{
				continue;
			}
			boolean allAccountedFor = groupKeys.stream()
				.allMatch(k -> k == candidate || byproducts.contains(k));
			if (allAccountedFor)
			{
				return candidate;
			}
		}
		return null;
	}
}
