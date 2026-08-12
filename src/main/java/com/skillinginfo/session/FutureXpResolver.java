package com.skillinginfo.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * The catalogue of what each item can be turned into, and for how much XP
 * (SPEC.md §33), kept deliberately narrow.
 * <p>
 * §35 forbids forcing a downstream mapping where an item has several
 * legitimate uses - and it names logs (Firemaking/Fletching/Construction)
 * as its own example, which covers most gathering output. The resolution
 * is to catalogue each item's *concrete products* and let the player pick
 * one per item, the way the banked-experience plugin does (§5). "Logs →
 * Fletching" has no single XP value; "Logs → Longbow (u)" is exactly 10.
 * Where an item genuinely has one use (raw fish), there's nothing to pick
 * and it simply always resolves.
 * <p>
 * Anything absent here shows no projection at all rather than a guessed
 * one. Ores are omitted because bars need coal at varying ratios, and
 * herbs because they need secondaries - neither has an honest per-item
 * value, so undercovering is the correct outcome.
 * <p>
 * XP values verified against TheStonedTurtle's banked-experience plugin
 * (BSD 2-Clause, §5) rather than written from memory. Only unnoted item
 * ids are catalogued; noted stacks ({@code ItemID.Cert.*}) are a
 * different id and won't resolve, which is an accepted limitation.
 */
public final class FutureXpResolver
{
	/** Gilded altar with both burners lit yields 350% of the bury value. */
	private static final double GILDED_ALTAR_MULTIPLIER = 3.5;

	private static final Map<Integer, List<ItemUse>> USES = new HashMap<>();

	private static void cook(int itemId, double xp)
	{
		USES.put(itemId, Collections.singletonList(new ItemUse("COOK", "Cook", Skill.COOKING, xp)));
	}

	/** Logs that can only be burned (teak/mahogany etc. aren't fletchable into bows). */
	private static void burnOnly(int itemId, double burnXp)
	{
		USES.put(itemId, Collections.singletonList(new ItemUse("BURN", "Burn", Skill.FIREMAKING, burnXp)));
	}

	private static void logWithBows(int itemId, double burnXp, double shortbowXp, double longbowXp)
	{
		USES.put(itemId, Arrays.asList(
			new ItemUse("BURN", "Burn", Skill.FIREMAKING, burnXp),
			new ItemUse("SHORTBOW", "Shortbow (u)", Skill.FLETCHING, shortbowXp),
			new ItemUse("LONGBOW", "Longbow (u)", Skill.FLETCHING, longbowXp)));
	}

	/**
	 * Ore → bar. The smelting XP is credited per <em>primary</em> ore, so a
	 * steel bar's 17.5 counts once against the iron ore that made it, not
	 * against the two coal it also consumed - otherwise the same bar would
	 * be counted three times over from one session's mining.
	 */
	private static void smelt(int oreId, ItemUse... uses)
	{
		USES.put(oreId, Arrays.asList(uses));
	}

	private static ItemUse bar(String id, String label, double xp)
	{
		return new ItemUse(id, label, Skill.SMITHING, xp);
	}

	private static void bones(int itemId, double buryXp)
	{
		USES.put(itemId, Arrays.asList(
			new ItemUse("BURY", "Bury", Skill.PRAYER, buryXp),
			new ItemUse("GILDED_ALTAR", "Gilded altar", Skill.PRAYER, buryXp * GILDED_ALTAR_MULTIPLIER)));
	}

	static
	{
		// Smithing. Ores are the case that makes the product picker earn its
		// keep: an iron ore is worth 12.5 XP as an iron bar or 17.5 as a
		// steel bar, and only the player knows which they intend. §35 says
		// don't guess between them - so don't.
		smelt(ItemID.COPPER_ORE, bar("BRONZE_BAR", "Bronze bar", 6.2));
		smelt(ItemID.TIN_ORE, bar("BRONZE_BAR", "Bronze bar", 6.2));
		smelt(ItemID.IRON_ORE,
			bar("IRON_BAR", "Iron bar", 12.5),
			bar("STEEL_BAR", "Steel bar", 17.5));
		smelt(ItemID.SILVER_ORE, bar("SILVER_BAR", "Silver bar", 13.7));
		smelt(ItemID.GOLD_ORE,
			bar("GOLD_BAR", "Gold bar", 22.5),
			bar("GOLD_BAR_GAUNTLETS", "Gold bar (gauntlets)", 56.2));
		smelt(ItemID.MITHRIL_ORE, bar("MITHRIL_BAR", "Mithril bar", 30.0));
		smelt(ItemID.ADAMANTITE_ORE, bar("ADAMANT_BAR", "Adamant bar", 37.5));
		smelt(ItemID.RUNITE_ORE, bar("RUNE_BAR", "Rune bar", 50.0));

		// Deliberately absent: coal, which is a secondary rather than a
		// product in its own right, and clay, whose uses don't resolve to a
		// single XP value (§35).

		// Cooking - one legitimate use, so nothing to choose.
		cook(ItemID.RAW_SHRIMP, 30.0);
		cook(ItemID.RAW_ANCHOVIES, 30.0);
		cook(ItemID.RAW_SARDINE, 40.0);
		cook(ItemID.RAW_HERRING, 50.0);
		cook(ItemID.RAW_MACKEREL, 60.0);
		cook(ItemID.RAW_TROUT, 70.0);
		cook(ItemID.RAW_COD, 75.0);
		cook(ItemID.RAW_PIKE, 80.0);
		cook(ItemID.RAW_SALMON, 90.0);
		cook(ItemID.RAW_TUNA, 100.0);
		cook(ItemID.RAW_LOBSTER, 120.0);
		cook(ItemID.RAW_BASS, 130.0);
		cook(ItemID.RAW_SWORDFISH, 140.0);
		cook(ItemID.RAW_MONKFISH, 150.0);
		cook(ItemID.TBWT_RAW_KARAMBWAN, 190.0);
		cook(ItemID.RAW_SHARK, 210.0);

		// Logs - burn or fletch. Construction is deliberately absent: the
		// XP comes from building furniture, not from the plank, so there's
		// no honest per-log value to offer.
		logWithBows(ItemID.LOGS, 40.0, 5.0, 10.0);
		logWithBows(ItemID.OAK_LOGS, 60.0, 16.5, 25.0);
		logWithBows(ItemID.WILLOW_LOGS, 90.0, 33.3, 41.5);
		logWithBows(ItemID.MAPLE_LOGS, 135.0, 50.0, 58.3);
		logWithBows(ItemID.YEW_LOGS, 202.5, 67.5, 75.0);
		logWithBows(ItemID.MAGIC_LOGS, 303.8, 83.3, 91.5);

		burnOnly(ItemID.ACHEY_TREE_LOGS, 40.0);
		burnOnly(ItemID.BLISTERWOOD_LOGS, 96.0);
		burnOnly(ItemID.TEAK_LOGS, 105.0);
		burnOnly(ItemID.JATOBA_LOGS, 120.0);
		burnOnly(ItemID.ARCTIC_PINE_LOG, 125.0);
		burnOnly(ItemID.MAHOGANY_LOGS, 157.5);
		burnOnly(ItemID.CAMPHOR_LOGS, 180.0);
		burnOnly(ItemID.IRONWOOD_LOGS, 220.5);
		burnOnly(ItemID.ROSEWOOD_LOGS, 268.0);
		burnOnly(ItemID.REDWOOD_LOGS, 350.0);

		// Prayer
		bones(ItemID.BONES, 4.5);
		bones(ItemID.WOLF_BONES, 4.5);
		bones(ItemID.BAT_BONES, 5.3);
		bones(ItemID.BIG_BONES, 15.0);
		bones(ItemID.ZOGRE_BONES, 22.5);
		bones(ItemID.BABYDRAGON_BONES, 30.0);
		bones(ItemID.WYRM_BONES, 50.0);
		bones(ItemID.DRAGON_BONES, 72.0);
		bones(ItemID.WYVERN_BONES, 72.0);
		bones(ItemID.DRAKE_BONES, 80.0);
		bones(ItemID.LAVA_DRAGON_BONES, 85.0);
		bones(ItemID.FROST_DRAGON_BONES, 100.0);
		bones(ItemID.HYDRA_BONES, 110.0);
		bones(ItemID.DAGANNOTH_KING_BONES, 125.0);
		bones(ItemID.DRAGON_BONES_SUPERIOR, 150.0);
	}

	private FutureXpResolver()
	{
	}

	/**
	 * @return the real (non-Off) products this item can become, or an empty
	 * list when it isn't catalogued. Empty is a common and correct answer -
	 * §35's whole point is that undercovering beats guessing.
	 */
	public static List<ItemUse> getUses(int itemId)
	{
		return USES.getOrDefault(itemId, Collections.emptyList());
	}

	/**
	 * @return {@link #getUses} plus an explicit Off entry, for populating a
	 * selector. Only meaningful when the item has a genuine choice to make.
	 */
	public static List<ItemUse> getSelectableUses(int itemId)
	{
		List<ItemUse> uses = getUses(itemId);
		if (uses.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ItemUse> selectable = new ArrayList<>(uses);
		selectable.add(ItemUse.OFF);
		return selectable;
	}

	/** True when the player has a real decision to make about this item. */
	public static boolean hasChoice(int itemId)
	{
		return getUses(itemId).size() > 1;
	}

	/** The use assumed when the player hasn't chosen one - the first listed. */
	public static ItemUse getDefaultUse(int itemId)
	{
		List<ItemUse> uses = getUses(itemId);
		return uses.isEmpty() ? null : uses.get(0);
	}

	/** Look up a use by its persisted id, or null if it no longer exists. */
	public static ItemUse findUse(int itemId, String useId)
	{
		if (ItemUse.OFF.id.equals(useId))
		{
			return ItemUse.OFF;
		}
		for (ItemUse use : getUses(itemId))
		{
			if (use.id.equals(useId))
			{
				return use;
			}
		}
		return null;
	}
}
