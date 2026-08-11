package com.skillinginfo.session;

import net.runelite.api.Skill;

/**
 * One concrete thing a player might do with an item, and the XP it yields
 * (SPEC.md §33/§35).
 * <p>
 * The unit here is a specific *product*, not a skill - that's what makes
 * §35 tractable. "Logs → Fletching" has no single XP value, but "Logs →
 * Longbow (u)" is exactly 10 XP. Ambiguity is resolved by letting the
 * player pick the product, rather than by the plugin assuming one.
 */
public final class ItemUse
{
	/**
	 * Stable identifier used as the persisted value - must not change once
	 * shipped, since it's what's written to the user's config.
	 */
	public final String id;
	public final String label;
	public final Skill skill;
	public final double xpPerItem;

	ItemUse(String id, String label, Skill skill, double xpPerItem)
	{
		this.id = id;
		this.label = label;
		this.skill = skill;
		this.xpPerItem = xpPerItem;
	}

	/** Sentinel for "don't project any future XP for this item". */
	public static final ItemUse OFF = new ItemUse("OFF", "Off", null, 0);

	/**
	 * Selection relies on identity (the catalogue hands out singletons), so
	 * this exists purely as a display fallback - without it, any renderer
	 * that didn't handle the value would show an object hash.
	 */
	@Override
	public String toString()
	{
		return label;
	}
}
