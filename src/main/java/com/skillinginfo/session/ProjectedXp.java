package com.skillinginfo.session;

import lombok.Getter;
import net.runelite.api.Skill;

/**
 * One item's projected XP, <b>frozen at the moment the session ended</b>
 * (SPEC.md §33/§44).
 * <p>
 * This is the point of the class. Future XP depends on a choice the player
 * makes - iron ore could become iron bars or steel bars - and that choice
 * can be changed later. Recomputing history against the current choice
 * would silently rewrite the past: switch to steel bars tomorrow and every
 * mining session you ever recorded would retroactively claim a different
 * number. So the resolved figure is captured per session and never
 * recalculated, while the selection itself carries forward to affect only
 * future sessions.
 * <p>
 * {@code useLabel} is stored alongside {@code useId} deliberately. The id
 * is what the selection means; the label is what it was <em>called</em> at
 * the time, so an old session still reads correctly even if the catalogue
 * is renamed or reorganised in a later version.
 * <p>
 * None of this displaces §34: the raw item counts stay in the session's
 * item flow and remain authoritative. This is the derived figure recorded
 * next to them, not instead of them.
 */
@Getter
public class ProjectedXp
{
	private final int itemId;

	/** The quantity this projection was based on, at the time. */
	private final int quantity;

	/** Stable id of the selected product (see {@link ItemUse#id}). */
	private final String useId;

	/** How that product was labelled when the session ended. */
	private final String useLabel;

	private final Skill skill;
	private final double xp;

	/** §33's confidence tier, as it stood: banked beats merely retained. */
	private final String confidence;

	ProjectedXp(int itemId, int quantity, String useId, String useLabel, Skill skill, double xp, String confidence)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.useId = useId;
		this.useLabel = useLabel;
		this.skill = skill;
		this.xp = xp;
		this.confidence = confidence;
	}
}
