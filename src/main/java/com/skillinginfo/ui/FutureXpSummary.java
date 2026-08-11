package com.skillinginfo.ui;

import com.skillinginfo.SkillingInfoConfig;
import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.FutureXpResolver;
import com.skillinginfo.session.ItemFlowEntry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Aggregates a session's item flow into per-skill future-XP totals
 * (SPEC.md §33), carrying the confidence tier with them.
 * <p>
 * §33 requires the confidence ordering to be a first-class property, not
 * just display wording: confirmed-banked quantity is preferred over
 * merely-retained, and the label has to say which so an estimate can
 * never read as earned XP.
 */
final class FutureXpSummary
{
	/** SPEC.md §33's confidence tiers, weakest last. */
	enum Confidence
	{
		CONFIRMED_BANKED("banked"),
		RETAINED("retained");

		final String label;

		Confidence(String label)
		{
			this.label = label;
		}
	}

	static final class Row
	{
		final Skill skill;
		final double xp;
		final Confidence confidence;

		Row(Skill skill, double xp, Confidence confidence)
		{
			this.skill = skill;
			this.xp = xp;
			this.confidence = confidence;
		}

		String format()
		{
			return String.format("%s  +%s  (%s)", skill.getName(), formatXp(xp), confidence.label);
		}
	}

	private FutureXpSummary()
	{
	}

	static List<Row> build(ActivitySession session, SkillingInfoConfig config)
	{
		Map<Skill, Double> totals = new EnumMap<>(Skill.class);
		Map<Skill, Confidence> confidences = new EnumMap<>(Skill.class);

		for (ItemFlowEntry entry : session.getItemFlow())
		{
			FutureXpResolver.FutureXp future = FutureXpResolver.resolve(entry.getItemId(), config);
			if (future == null)
			{
				continue;
			}

			// §33: prefer confirmed-banked quantity; fall back to retained.
			int qty = entry.getBanked() > 0 ? entry.getBanked() : entry.getNetRetained();
			if (qty <= 0)
			{
				continue;
			}
			Confidence confidence = entry.getBanked() > 0 ? Confidence.CONFIRMED_BANKED : Confidence.RETAINED;

			totals.merge(future.skill, qty * future.xpPerItem, Double::sum);
			// a skill's headline can only be as strong as its weakest
			// contributing item - never label a mixed total "banked"
			confidences.merge(future.skill, confidence,
				(a, b) -> a.ordinal() >= b.ordinal() ? a : b);
		}

		List<Row> rows = new ArrayList<>();
		for (Map.Entry<Skill, Double> entry : totals.entrySet())
		{
			rows.add(new Row(entry.getKey(), entry.getValue(), confidences.get(entry.getKey())));
		}
		return rows;
	}

	private static String formatXp(double xp)
	{
		if (xp >= 1_000_000)
		{
			return String.format("%.1fm", xp / 1_000_000);
		}
		if (xp >= 1_000)
		{
			return String.format("%.1fk", xp / 1_000);
		}
		return String.format("%,d", Math.round(xp));
	}
}
