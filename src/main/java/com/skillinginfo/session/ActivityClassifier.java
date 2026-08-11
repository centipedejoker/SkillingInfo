package com.skillinginfo.session;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Names what the player is actually doing within a skill (SPEC.md §16),
 * e.g. "Oak trees" rather than just "Woodcutting".
 * <p>
 * Classification is derived from what the session actually *produced* -
 * the most conservative deterministic signal available (§16), and one
 * already tracked reliably since Phase 2. No animation ids, object ids or
 * region checks are involved, so there's nothing to break when Jagex
 * renumbers content, and no guessing when signals conflict.
 * <p>
 * Where a method produces several items (fly fishing yields both trout
 * and salmon), every contributing item maps to the same activity name, so
 * a mixed catch still classifies cleanly. Where the produced item genuinely
 * doesn't identify a method, or nothing has been produced yet, the answer
 * stays {@link #UNCLASSIFIED} - §15/§16 are explicit that this is a
 * correct outcome rather than a failure.
 */
public final class ActivityClassifier
{
	public static final String UNCLASSIFIED = "Unclassified";

	private static final Map<Integer, String> WOODCUTTING = new HashMap<>();
	private static final Map<Integer, String> FISHING = new HashMap<>();

	static
	{
		WOODCUTTING.put(ItemID.LOGS, "Regular trees");
		WOODCUTTING.put(ItemID.ACHEY_TREE_LOGS, "Achey trees");
		WOODCUTTING.put(ItemID.OAK_LOGS, "Oak trees");
		WOODCUTTING.put(ItemID.WILLOW_LOGS, "Willow trees");
		WOODCUTTING.put(ItemID.TEAK_LOGS, "Teak trees");
		WOODCUTTING.put(ItemID.MAPLE_LOGS, "Maple trees");
		WOODCUTTING.put(ItemID.MAHOGANY_LOGS, "Mahogany trees");
		WOODCUTTING.put(ItemID.ARCTIC_PINE_LOG, "Arctic pines");
		WOODCUTTING.put(ItemID.YEW_LOGS, "Yew trees");
		WOODCUTTING.put(ItemID.MAGIC_LOGS, "Magic trees");
		WOODCUTTING.put(ItemID.REDWOOD_LOGS, "Redwood trees");
		WOODCUTTING.put(ItemID.BLISTERWOOD_LOGS, "Blisterwood");
		WOODCUTTING.put(ItemID.JATOBA_LOGS, "Jatoba trees");
		WOODCUTTING.put(ItemID.CAMPHOR_LOGS, "Camphor trees");
		WOODCUTTING.put(ItemID.IRONWOOD_LOGS, "Ironwood trees");
		WOODCUTTING.put(ItemID.ROSEWOOD_LOGS, "Rosewood trees");

		// Fishing is named by method rather than by fish, since that's what
		// the player would call the activity. Spots yielding two fish map
		// both to the same name so a mixed catch still classifies.
		FISHING.put(ItemID.RAW_SHRIMP, "Net fishing");
		FISHING.put(ItemID.RAW_ANCHOVIES, "Net fishing");
		FISHING.put(ItemID.RAW_SARDINE, "Bait fishing");
		FISHING.put(ItemID.RAW_HERRING, "Bait fishing");
		FISHING.put(ItemID.RAW_TROUT, "Fly fishing");
		FISHING.put(ItemID.RAW_SALMON, "Fly fishing");
		FISHING.put(ItemID.RAW_PIKE, "Pike");
		FISHING.put(ItemID.RAW_MACKEREL, "Big net fishing");
		FISHING.put(ItemID.RAW_COD, "Big net fishing");
		FISHING.put(ItemID.RAW_BASS, "Big net fishing");
		FISHING.put(ItemID.RAW_LOBSTER, "Lobster cages");
		FISHING.put(ItemID.RAW_TUNA, "Harpoon fishing");
		FISHING.put(ItemID.RAW_SWORDFISH, "Harpoon fishing");
		FISHING.put(ItemID.RAW_MONKFISH, "Monkfish");
		FISHING.put(ItemID.RAW_SHARK, "Sharks");
		FISHING.put(ItemID.TBWT_RAW_KARAMBWAN, "Karambwans");
	}

	private ActivityClassifier()
	{
	}

	/**
	 * @return a display name for what the session is doing, or
	 * {@link #UNCLASSIFIED}. Determined by the single most-produced item,
	 * so an incidental by-catch can't rename the activity.
	 */
	public static String classify(ActivitySession session)
	{
		Map<Integer, String> table = tableFor(session.getSkill());
		if (table == null)
		{
			return UNCLASSIFIED;
		}

		String best = null;
		int bestQty = 0;
		for (ItemFlowEntry entry : session.getItemFlow())
		{
			String name = table.get(entry.getItemId());
			if (name == null)
			{
				continue;
			}
			// generated, not acquired - what the activity produced is what
			// identifies it; something picked up off the ground isn't
			// evidence of the method being used
			if (entry.getGenerated() > bestQty)
			{
				bestQty = entry.getGenerated();
				best = name;
			}
		}

		return best == null ? UNCLASSIFIED : best;
	}

	private static Map<Integer, String> tableFor(Skill skill)
	{
		if (skill == Skill.WOODCUTTING)
		{
			return WOODCUTTING;
		}
		if (skill == Skill.FISHING)
		{
			return FISHING;
		}
		return null;
	}

	/**
	 * The noun for one unit of this skill's output, for rate display
	 * (§40) - "logs/hr" reads better than a generic "actions/hr".
	 */
	public static String outputNoun(Skill skill)
	{
		if (skill == Skill.WOODCUTTING)
		{
			return "Logs";
		}
		if (skill == Skill.FISHING)
		{
			return "Catches";
		}
		return "Produced";
	}
}
