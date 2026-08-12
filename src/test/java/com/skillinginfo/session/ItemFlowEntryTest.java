package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Covers SPEC.md §28's net-retained arithmetic now that consumption
 * (§18) participates in it. The production-skill cases are the reason
 * ITEM_CONSUMED existed as a blocker at all: without it those sessions
 * reported the product gained and no sign of the ingredient spent.
 */
public class ItemFlowEntryTest
{
	private static ActivitySession session()
	{
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.COOKING);
		return s;
	}

	private static ItemFlowEntry entry(ActivitySession s, int itemId)
	{
		return s.getItemFlow().stream()
			.filter(e -> e.getItemId() == itemId)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no flow entry for " + itemId));
	}

	@Test
	public void consumingStockYouAlreadyHadNetsToZeroNotNegative()
	{
		// brought 100 raw sharks from the bank and cooked them: the session
		// never acquired them, so retention floors at zero rather than
		// inventing a negative
		ActivitySession s = session();
		s.addConsumed(ItemID.RAW_SHARK, 100);

		assertEquals(0, entry(s, ItemID.RAW_SHARK).getNetRetained());
		assertEquals(100, entry(s, ItemID.RAW_SHARK).getConsumed());
	}

	@Test
	public void cookingWhatYouCaughtNetsOutAcrossBothItems()
	{
		// the case that made production skills dishonest before §18: the
		// raw fish must not still read as retained once it's been cooked
		ActivitySession s = session();
		s.addGenerated(ItemID.RAW_SHARK, 100);
		s.addConsumed(ItemID.RAW_SHARK, 100);
		s.addGenerated(ItemID.SHARK, 100);

		assertEquals(0, entry(s, ItemID.RAW_SHARK).getNetRetained());
		assertEquals(100, entry(s, ItemID.SHARK).getNetRetained());
		assertEquals(100, s.getTotalConsumed());
	}

	@Test
	public void partialConsumptionLeavesTheRemainder()
	{
		ActivitySession s = session();
		s.addGenerated(ItemID.RAW_SHARK, 100);
		s.addConsumed(ItemID.RAW_SHARK, 60);

		assertEquals(40, entry(s, ItemID.RAW_SHARK).getNetRetained());
	}

	@Test
	public void bankingDoesNotReduceRetentionButConsumptionDoes()
	{
		// §33: a banked item is the strongest form of account gain, not a
		// loss - only consumption actually removes it
		ActivitySession banked = session();
		banked.addGenerated(ItemID.SHARK, 50);
		banked.addBanked(ItemID.SHARK, 50);
		assertEquals(50, entry(banked, ItemID.SHARK).getNetRetained());

		ActivitySession eaten = session();
		eaten.addGenerated(ItemID.SHARK, 50);
		eaten.addConsumed(ItemID.SHARK, 50);
		assertEquals(0, entry(eaten, ItemID.SHARK).getNetRetained());
	}

	@Test
	public void consumptionReducesWhatBankCorrelationWillClaim()
	{
		// §25a's ledger must not offer to bank something already eaten
		ActivitySession s = session();
		s.addGenerated(ItemID.SHARK, 50);
		s.addConsumed(ItemID.SHARK, 20);

		assertEquals(30, s.getOutstandingForBanking(ItemID.SHARK));
	}
}
