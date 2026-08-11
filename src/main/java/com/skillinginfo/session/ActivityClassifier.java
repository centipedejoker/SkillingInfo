package com.skillinginfo.session;

import java.util.EnumMap;
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

	/**
	 * skill → (produced itemId → activity name). Adding a skill is purely
	 * a data entry here; no new mechanism is involved.
	 */
	private static final Map<Skill, Map<Integer, String>> TABLES = new EnumMap<>(Skill.class);
	private static final Map<Skill, String> OUTPUT_NOUNS = new EnumMap<>(Skill.class);

	private static Map<Integer, String> table(Skill skill, String outputNoun)
	{
		OUTPUT_NOUNS.put(skill, outputNoun);
		return TABLES.computeIfAbsent(skill, s -> new HashMap<>());
	}

	static
	{
		Map<Integer, String> WOODCUTTING = table(Skill.WOODCUTTING, "Logs");
		Map<Integer, String> FISHING = table(Skill.FISHING, "Catches");
		Map<Integer, String> MINING = table(Skill.MINING, "Ores");
		Map<Integer, String> HUNTER = table(Skill.HUNTER, "Catches");
		Map<Integer, String> FARMING = table(Skill.FARMING, "Harvested");
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

		MINING.put(ItemID.COPPER_ORE, "Copper");
		MINING.put(ItemID.TIN_ORE, "Tin");
		MINING.put(ItemID.CLAY, "Clay");
		MINING.put(ItemID.IRON_ORE, "Iron ore");
		MINING.put(ItemID.SILVER_ORE, "Silver ore");
		MINING.put(ItemID.COAL, "Coal");
		MINING.put(ItemID.GOLD_ORE, "Gold ore");
		MINING.put(ItemID.MITHRIL_ORE, "Mithril ore");
		MINING.put(ItemID.ADAMANTITE_ORE, "Adamantite ore");
		MINING.put(ItemID.RUNITE_ORE, "Runite ore");
		MINING.put(ItemID.BLURITE_ORE, "Blurite ore");
		MINING.put(ItemID.LOVAKITE_ORE, "Lovakite ore");
		MINING.put(ItemID.AMETHYST, "Amethyst");
		MINING.put(ItemID.BLANKRUNE, "Essence");
		MINING.put(ItemID.BLANKRUNE_HIGH, "Essence");

		HUNTER.put(ItemID.CHINCHOMPA_CAPTURED, "Chinchompas");
		HUNTER.put(ItemID.CHINCHOMPA_BIG_CAPTURED, "Red chinchompas");
		HUNTER.put(ItemID.CHINCHOMPA_BLACK, "Black chinchompas");
		HUNTER.put(ItemID.ORANGE_SALAMANDER, "Orange salamanders");
		HUNTER.put(ItemID.RED_SALAMANDER, "Red salamanders");
		HUNTER.put(ItemID.BLACK_SALAMANDER, "Black salamanders");

		// Farming is named by what's harvested. Seeds are consumed at
		// planting, typically long before the session that harvests them,
		// so the ledger stays one-sided in a way that's actually correct.
		FARMING.put(ItemID.UNIDENTIFIED_GUAM, "Guam");
		FARMING.put(ItemID.UNIDENTIFIED_MARENTILL, "Marrentill");
		FARMING.put(ItemID.UNIDENTIFIED_TARROMIN, "Tarromin");
		FARMING.put(ItemID.UNIDENTIFIED_HARRALANDER, "Harralander");
		FARMING.put(ItemID.UNIDENTIFIED_RANARR, "Ranarr");
		FARMING.put(ItemID.UNIDENTIFIED_TOADFLAX, "Toadflax");
		FARMING.put(ItemID.UNIDENTIFIED_IRIT, "Irit");
		FARMING.put(ItemID.UNIDENTIFIED_AVANTOE, "Avantoe");
		FARMING.put(ItemID.UNIDENTIFIED_KWUARM, "Kwuarm");
		FARMING.put(ItemID.UNIDENTIFIED_SNAPDRAGON, "Snapdragon");
		FARMING.put(ItemID.UNIDENTIFIED_CADANTINE, "Cadantine");
		FARMING.put(ItemID.UNIDENTIFIED_LANTADYME, "Lantadyme");
		FARMING.put(ItemID.UNIDENTIFIED_DWARF_WEED, "Dwarf weed");
		FARMING.put(ItemID.UNIDENTIFIED_TORSTOL, "Torstol");
		FARMING.put(ItemID.UNIDENTIFIED_HUASCA, "Huasca");
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
		return skill == null ? null : TABLES.get(skill);
	}

	/**
	 * The noun for one unit of this skill's output, for rate display
	 * (§40) - "logs/hr" reads better than a generic "actions/hr".
	 */
	public static String outputNoun(Skill skill)
	{
		return skill == null ? "Produced" : OUTPUT_NOUNS.getOrDefault(skill, "Produced");
	}
}
