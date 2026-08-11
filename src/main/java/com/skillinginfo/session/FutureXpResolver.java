package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Maps a banked/retained item to the XP it would eventually yield
 * (SPEC.md §33), deliberately narrowly.
 * <p>
 * §35 forbids forcing a downstream mapping where an item has several
 * legitimate uses, which rules out most gathering output on its face -
 * logs alone can go to Firemaking, Fletching or Construction. The
 * resolution (§33 [v7]) is that genuinely unambiguous items are mapped
 * unconditionally, while ambiguous ones are driven by explicit user
 * config and can be switched off entirely. Anything not covered here
 * simply has no future-XP projection rather than a guessed one.
 * <p>
 * Deliberately NOT covered, and why:
 * <ul>
 *   <li>Fletching/Construction from logs - no single XP value per log,
 *       it depends entirely what you make.</li>
 *   <li>Ores → Smithing - bars need coal at varying ratios, so a
 *       per-ore value would be fiction.</li>
 *   <li>Herbs → Herblore - requires secondaries.</li>
 * </ul>
 * <p>
 * XP values verified against TheStonedTurtle's banked-experience plugin
 * (BSD 2-Clause), which maintains this data comprehensively - see §5.
 * Only unnoted item ids are mapped; noted stacks ({@code ItemID.Cert.*})
 * are a different id and won't resolve, which is an accepted limitation.
 */
public final class FutureXpResolver
{
	/** Result of a successful lookup: which skill, and how much XP per item. */
	public static final class FutureXp
	{
		public final Skill skill;
		public final double xpPerItem;

		FutureXp(Skill skill, double xpPerItem)
		{
			this.skill = skill;
			this.xpPerItem = xpPerItem;
		}
	}

	/** Gilded altar with both burners lit yields 350% of the bury value. */
	private static final double GILDED_ALTAR_MULTIPLIER = 3.5;

	private static final Map<Integer, Double> COOKING_XP = new HashMap<>();
	private static final Map<Integer, Double> FIREMAKING_XP = new HashMap<>();
	private static final Map<Integer, Double> BONE_BURY_XP = new HashMap<>();

	static
	{
		// Cooking - unambiguous, always on. Raw fish has exactly one use.
		COOKING_XP.put(ItemID.RAW_SHRIMP, 30.0);
		COOKING_XP.put(ItemID.RAW_ANCHOVIES, 30.0);
		COOKING_XP.put(ItemID.RAW_SARDINE, 40.0);
		COOKING_XP.put(ItemID.RAW_HERRING, 50.0);
		COOKING_XP.put(ItemID.RAW_MACKEREL, 60.0);
		COOKING_XP.put(ItemID.RAW_TROUT, 70.0);
		COOKING_XP.put(ItemID.RAW_COD, 75.0);
		COOKING_XP.put(ItemID.RAW_PIKE, 80.0);
		COOKING_XP.put(ItemID.RAW_SALMON, 90.0);
		COOKING_XP.put(ItemID.RAW_TUNA, 100.0);
		COOKING_XP.put(ItemID.RAW_LOBSTER, 120.0);
		COOKING_XP.put(ItemID.RAW_BASS, 130.0);
		COOKING_XP.put(ItemID.RAW_SWORDFISH, 140.0);
		COOKING_XP.put(ItemID.RAW_MONKFISH, 150.0);
		COOKING_XP.put(ItemID.TBWT_RAW_KARAMBWAN, 190.0);
		COOKING_XP.put(ItemID.RAW_SHARK, 210.0);

		// Firemaking - only applies when the user has selected it for logs.
		FIREMAKING_XP.put(ItemID.LOGS, 40.0);
		FIREMAKING_XP.put(ItemID.ACHEY_TREE_LOGS, 40.0);
		FIREMAKING_XP.put(ItemID.OAK_LOGS, 60.0);
		FIREMAKING_XP.put(ItemID.WILLOW_LOGS, 90.0);
		FIREMAKING_XP.put(ItemID.BLISTERWOOD_LOGS, 96.0);
		FIREMAKING_XP.put(ItemID.TEAK_LOGS, 105.0);
		FIREMAKING_XP.put(ItemID.JATOBA_LOGS, 120.0);
		FIREMAKING_XP.put(ItemID.ARCTIC_PINE_LOG, 125.0);
		FIREMAKING_XP.put(ItemID.MAPLE_LOGS, 135.0);
		FIREMAKING_XP.put(ItemID.MAHOGANY_LOGS, 157.5);
		FIREMAKING_XP.put(ItemID.CAMPHOR_LOGS, 180.0);
		FIREMAKING_XP.put(ItemID.YEW_LOGS, 202.5);
		FIREMAKING_XP.put(ItemID.IRONWOOD_LOGS, 220.5);
		FIREMAKING_XP.put(ItemID.ROSEWOOD_LOGS, 268.0);
		FIREMAKING_XP.put(ItemID.MAGIC_LOGS, 303.8);
		FIREMAKING_XP.put(ItemID.REDWOOD_LOGS, 350.0);

		// Prayer - bury values; the gilded-altar option scales these.
		BONE_BURY_XP.put(ItemID.BONES, 4.5);
		BONE_BURY_XP.put(ItemID.WOLF_BONES, 4.5);
		BONE_BURY_XP.put(ItemID.BAT_BONES, 5.3);
		BONE_BURY_XP.put(ItemID.BIG_BONES, 15.0);
		BONE_BURY_XP.put(ItemID.ZOGRE_BONES, 22.5);
		BONE_BURY_XP.put(ItemID.BABYDRAGON_BONES, 30.0);
		BONE_BURY_XP.put(ItemID.WYRM_BONES, 50.0);
		BONE_BURY_XP.put(ItemID.DRAGON_BONES, 72.0);
		BONE_BURY_XP.put(ItemID.WYVERN_BONES, 72.0);
		BONE_BURY_XP.put(ItemID.DRAKE_BONES, 80.0);
		BONE_BURY_XP.put(ItemID.LAVA_DRAGON_BONES, 85.0);
		BONE_BURY_XP.put(ItemID.FROST_DRAGON_BONES, 100.0);
		BONE_BURY_XP.put(ItemID.HYDRA_BONES, 110.0);
		BONE_BURY_XP.put(ItemID.DAGANNOTH_KING_BONES, 125.0);
		BONE_BURY_XP.put(ItemID.DRAGON_BONES_SUPERIOR, 150.0);
	}

	private FutureXpResolver()
	{
	}

	/**
	 * @return the future XP this item represents, or null when the item
	 * isn't mapped or the user has switched its category off. Null is the
	 * correct, common answer - §35's whole point is that undercovering
	 * beats guessing.
	 */
	public static FutureXp resolve(int itemId, SkillingInfoConfig config)
	{
		Double cooking = COOKING_XP.get(itemId);
		if (cooking != null)
		{
			return new FutureXp(Skill.COOKING, cooking);
		}

		Double firemaking = FIREMAKING_XP.get(itemId);
		if (firemaking != null && config.logsFutureXp() == SkillingInfoConfig.LogsUse.FIREMAKING)
		{
			return new FutureXp(Skill.FIREMAKING, firemaking);
		}

		Double bury = BONE_BURY_XP.get(itemId);
		if (bury != null)
		{
			switch (config.bonesFutureXp())
			{
				case BURY:
					return new FutureXp(Skill.PRAYER, bury);
				case GILDED_ALTAR:
					return new FutureXp(Skill.PRAYER, bury * GILDED_ALTAR_MULTIPLIER);
				default:
					return null;
			}
		}

		return null;
	}
}
