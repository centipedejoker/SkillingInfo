package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Locks in SPEC.md §35's contract: an item with several legitimate uses
 * must never get a projection forced on it. "Returns null" is a deliberate
 * behavioural promise here, not an oversight, so it's worth a test that
 * fails loudly if someone later "helpfully" fills the gaps in.
 */
public class FutureXpResolverTest
{
	private static SkillingInfoConfig config(SkillingInfoConfig.LogsUse logs, SkillingInfoConfig.BonesUse bones)
	{
		return new SkillingInfoConfig()
		{
			@Override
			public LogsUse logsFutureXp()
			{
				return logs;
			}

			@Override
			public BonesUse bonesFutureXp()
			{
				return bones;
			}
		};
	}

	private static SkillingInfoConfig defaults()
	{
		return config(SkillingInfoConfig.LogsUse.FIREMAKING, SkillingInfoConfig.BonesUse.BURY);
	}

	@Test
	public void unambiguousItemResolvesRegardlessOfConfig()
	{
		// raw fish has exactly one use, so no config gate applies
		FutureXpResolver.FutureXp result = FutureXpResolver.resolve(ItemID.RAW_SHARK,
			config(SkillingInfoConfig.LogsUse.OFF, SkillingInfoConfig.BonesUse.OFF));

		assertNotNull(result);
		assertEquals(Skill.COOKING, result.skill);
		assertEquals(210.0, result.xpPerItem, 0.001);
	}

	@Test
	public void logsResolveOnlyWhenTheUserOptedIn()
	{
		FutureXpResolver.FutureXp on = FutureXpResolver.resolve(ItemID.OAK_LOGS, defaults());
		assertNotNull(on);
		assertEquals(Skill.FIREMAKING, on.skill);
		assertEquals(60.0, on.xpPerItem, 0.001);

		FutureXpResolver.FutureXp off = FutureXpResolver.resolve(ItemID.OAK_LOGS,
			config(SkillingInfoConfig.LogsUse.OFF, SkillingInfoConfig.BonesUse.BURY));
		assertNull("logs must not be projected when switched off", off);
	}

	@Test
	public void gildedAltarScalesTheBuryValue()
	{
		FutureXpResolver.FutureXp bury = FutureXpResolver.resolve(ItemID.DRAGON_BONES, defaults());
		FutureXpResolver.FutureXp altar = FutureXpResolver.resolve(ItemID.DRAGON_BONES,
			config(SkillingInfoConfig.LogsUse.FIREMAKING, SkillingInfoConfig.BonesUse.GILDED_ALTAR));

		assertEquals(72.0, bury.xpPerItem, 0.001);
		assertEquals(72.0 * 3.5, altar.xpPerItem, 0.001);
		assertEquals(Skill.PRAYER, altar.skill);
	}

	@Test
	public void bonesCanBeSwitchedOff()
	{
		assertNull(FutureXpResolver.resolve(ItemID.BONES,
			config(SkillingInfoConfig.LogsUse.FIREMAKING, SkillingInfoConfig.BonesUse.OFF)));
	}

	@Test
	public void unmappedItemIsNotGuessed()
	{
		// §35's worked example is teak logs -> Construction/Firemaking/
		// Fletching. Ores are the same shape: mapping them to Smithing
		// would require inventing a coal ratio, so they must stay unmapped.
		assertNull("ore must not be projected to Smithing", FutureXpResolver.resolve(ItemID.IRON_ORE, defaults()));
		assertNull("herb must not be projected to Herblore", FutureXpResolver.resolve(ItemID.UNIDENTIFIED_GUAM, defaults()));
	}

	@Test
	public void notedItemsDoNotResolve()
	{
		// documented limitation: only unnoted ids are mapped
		assertNull(FutureXpResolver.resolve(ItemID.Cert.OAK_LOGS, defaults()));
	}
}
