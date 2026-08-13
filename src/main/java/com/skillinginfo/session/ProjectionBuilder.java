package com.skillinginfo.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Resolves a session's item flow into projected XP (SPEC.md §33), using the
 * product currently selected for each item.
 * <p>
 * The single place this resolution happens. The live panel calls it every
 * tick against the current selection; {@link SessionManager#stop()} calls it
 * once at finalisation and stores the result on the session, after which the
 * stored figures are what history shows forever - see {@link ProjectedXp}
 * for why history must not recompute.
 */
public final class ProjectionBuilder
{
	/** §33's confidence tiers. Confirmed-banked outranks merely-retained. */
	public static final String CONFIRMED_BANKED = "banked";
	public static final String RETAINED = "retained";

	private ProjectionBuilder()
	{
	}

	public static List<ProjectedXp> build(ActivitySession session, ItemUseStore itemUseStore)
	{
		List<ProjectedXp> projections = new ArrayList<>();

		for (ItemFlowEntry entry : session.getItemFlow())
		{
			ItemUse use = itemUseStore.get(entry.getItemId());
			if (use == null || use.skill == null)
			{
				// not catalogued, or the player switched this item off
				continue;
			}

			// §33 `[v9]`: the quantity is everything retained, and the
			// confidence describes it.
			//
			// This used to read `banked > 0 ? banked : netRetained`, which
			// looks like a preference for the stronger figure but is a
			// subset error: banking deliberately does not reduce net retained
			// (§28), so netRetained already *contains* banked. The moment
			// anything was banked, everything still in the inventory
			// vanished from the projection - fish 27 sharks, bank them, fish
			// 27 more, and it reported half of what was held. Worse, §33
			// freezes the projection at finalisation, so the wrong figure
			// was permanent.
			int qty = entry.getNetRetained();
			if (qty <= 0)
			{
				continue;
			}
			// the label can only claim "banked" when all of it is
			boolean fullyBanked = entry.getBanked() >= qty;

			projections.add(new ProjectedXp(
				entry.getItemId(),
				qty,
				use.id,
				use.label,
				use.skill,
				qty * use.xpPerItem,
				fullyBanked ? CONFIRMED_BANKED : RETAINED));
		}

		return projections;
	}

	/**
	 * Per-skill totals for display. A skill's headline can only be as strong
	 * as its weakest contributing item, so a mixed total is never labelled
	 * "banked".
	 */
	public static List<SkillTotal> totals(List<ProjectedXp> projections)
	{
		Map<Skill, SkillTotal> bySkill = new LinkedHashMap<>();
		for (ProjectedXp p : projections)
		{
			SkillTotal existing = bySkill.get(p.getSkill());
			if (existing == null)
			{
				bySkill.put(p.getSkill(), new SkillTotal(p.getSkill(), p.getXp(), p.getConfidence()));
			}
			else
			{
				String confidence = RETAINED.equals(p.getConfidence()) ? RETAINED : existing.confidence;
				bySkill.put(p.getSkill(), new SkillTotal(p.getSkill(), existing.xp + p.getXp(), confidence));
			}
		}
		return new ArrayList<>(bySkill.values());
	}

	public static final class SkillTotal
	{
		public final Skill skill;
		public final double xp;
		public final String confidence;

		SkillTotal(Skill skill, double xp, String confidence)
		{
			this.skill = skill;
			this.xp = xp;
			this.confidence = confidence;
		}
	}
}
