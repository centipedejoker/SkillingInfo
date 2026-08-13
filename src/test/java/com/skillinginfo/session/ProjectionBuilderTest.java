package com.skillinginfo.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers SPEC.md §33's projection, and specifically the property that makes
 * a recorded session trustworthy: <b>changing a product selection later must
 * never rewrite what an earlier session reported</b>.
 * <p>
 * Without that, switching iron ore from iron bars to steel bars would
 * silently restate the projected XP of every mining session ever recorded.
 */
public class ProjectionBuilderTest
{
	/** In-memory stand-in for the config-backed store. */
	private static final class FakeStore extends ItemUseStore
	{
		private final Map<Integer, ItemUse> chosen = new HashMap<>();

		FakeStore()
		{
			super(null);
		}

		void choose(int itemId, String useId)
		{
			chosen.put(itemId, FutureXpResolver.findUse(itemId, useId));
		}

		@Override
		public ItemUse get(int itemId)
		{
			ItemUse use = chosen.get(itemId);
			return use != null ? use : FutureXpResolver.getDefaultUse(itemId);
		}
	}

	private static ActivitySession sessionWithBankedLogs(int qty)
	{
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.WOODCUTTING);
		s.addGenerated(ItemID.OAK_LOGS, qty);
		s.addBanked(ItemID.OAK_LOGS, qty);
		return s;
	}

	@Test
	public void projectsAgainstTheSelectedProduct()
	{
		FakeStore store = new FakeStore();
		store.choose(ItemID.OAK_LOGS, "BURN");

		List<ProjectedXp> burn = ProjectionBuilder.build(sessionWithBankedLogs(100), store);
		assertEquals(1, burn.size());
		assertEquals(Skill.FIREMAKING, burn.get(0).getSkill());

		store.choose(ItemID.OAK_LOGS, "LONGBOW");
		List<ProjectedXp> fletch = ProjectionBuilder.build(sessionWithBankedLogs(100), store);
		assertEquals(Skill.FLETCHING, fletch.get(0).getSkill());

		assertTrue("a different product must yield a different figure",
			burn.get(0).getXp() != fletch.get(0).getXp());
	}

	@Test
	public void changingTheSelectionDoesNotRewriteAnAlreadyRecordedSession()
	{
		// this is the whole point of freezing the projection
		FakeStore store = new FakeStore();
		store.choose(ItemID.OAK_LOGS, "BURN");

		ActivitySession recorded = sessionWithBankedLogs(100);
		recorded.setProjection(ProjectionBuilder.build(recorded, store));

		double asRecorded = recorded.getProjection().get(0).getXp();
		Skill skillAsRecorded = recorded.getProjection().get(0).getSkill();
		String labelAsRecorded = recorded.getProjection().get(0).getUseLabel();

		// player changes their mind afterwards
		store.choose(ItemID.OAK_LOGS, "LONGBOW");

		assertEquals("recorded XP must not move", asRecorded, recorded.getProjection().get(0).getXp(), 0.001);
		assertEquals("recorded skill must not move", skillAsRecorded, recorded.getProjection().get(0).getSkill());
		assertEquals("the product it assumed must still be named",
			labelAsRecorded, recorded.getProjection().get(0).getUseLabel());
	}

	@Test
	public void theNewSelectionStillAppliesToLaterSessions()
	{
		FakeStore store = new FakeStore();
		store.choose(ItemID.OAK_LOGS, "BURN");

		ActivitySession old = sessionWithBankedLogs(100);
		old.setProjection(ProjectionBuilder.build(old, store));

		store.choose(ItemID.OAK_LOGS, "LONGBOW");

		ActivitySession fresh = sessionWithBankedLogs(100);
		fresh.setProjection(ProjectionBuilder.build(fresh, store));

		assertEquals(Skill.FIREMAKING, old.getProjection().get(0).getSkill());
		assertEquals(Skill.FLETCHING, fresh.getProjection().get(0).getSkill());
	}

	@Test
	public void aPartlyBankedStackIsProjectedInFull()
	{
		// The rule used to be `banked > 0 ? banked : netRetained`, which reads
		// like a preference for the stronger figure but is a subset error:
		// banking deliberately doesn't reduce net retained (§28), so
		// netRetained already contains banked. The moment anything was
		// banked, everything still in the inventory vanished from the
		// projection - and §33 freezes it at finalisation, so permanently.
		FakeStore store = new FakeStore();
		store.choose(ItemID.OAK_LOGS, "BURN");

		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.WOODCUTTING);
		s.addGenerated(ItemID.OAK_LOGS, 54);
		s.addBanked(ItemID.OAK_LOGS, 27); // one load banked, one still carried

		assertEquals("banking doesn't reduce what's held", 54, s.getTotalNetRetained());

		ProjectedXp projected = ProjectionBuilder.build(s, store).get(0);
		assertEquals(54, projected.getQuantity());
		assertEquals("and the label can't claim banked for the half that isn't",
			ProjectionBuilder.RETAINED, projected.getConfidence());
	}

	@Test
	public void bankedQuantityOutranksMerelyRetained()
	{
		FakeStore store = new FakeStore();
		store.choose(ItemID.OAK_LOGS, "BURN");

		ActivitySession banked = sessionWithBankedLogs(50);
		assertEquals(ProjectionBuilder.CONFIRMED_BANKED,
			ProjectionBuilder.build(banked, store).get(0).getConfidence());

		ActivitySession held = new ActivitySession();
		held.setSkill(Skill.WOODCUTTING);
		held.addGenerated(ItemID.OAK_LOGS, 50);
		assertEquals(ProjectionBuilder.RETAINED,
			ProjectionBuilder.build(held, store).get(0).getConfidence());
	}

	@Test
	public void ironOreOffersBothBarsAndProjectsTheChosenOne()
	{
		// the case that motivated the picker: only the player knows whether
		// their iron ore is destined for iron bars or steel
		assertTrue("iron ore must present a choice", FutureXpResolver.hasChoice(ItemID.IRON_ORE));

		FakeStore store = new FakeStore();
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.MINING);
		s.addGenerated(ItemID.IRON_ORE, 100);
		s.addBanked(ItemID.IRON_ORE, 100);

		store.choose(ItemID.IRON_ORE, "IRON_BAR");
		assertEquals(1250.0, ProjectionBuilder.build(s, store).get(0).getXp(), 0.001);

		store.choose(ItemID.IRON_ORE, "STEEL_BAR");
		assertEquals(1750.0, ProjectionBuilder.build(s, store).get(0).getXp(), 0.001);
	}

	@Test
	public void itemsWithNoHonestSingleValueAreLeftOut()
	{
		// §35: clay's uses don't resolve to one XP value, and coal is a
		// secondary rather than a product - neither should be guessed at
		assertTrue(FutureXpResolver.getUses(ItemID.CLAY).isEmpty());
		assertTrue(FutureXpResolver.getUses(ItemID.COAL).isEmpty());
	}

	@Test
	public void ratesAreScopedToThisSessionOnly()
	{
		// §14 [v7]: computed from the session's own active time. Reading
		// them from XP Tracker gave 15 xp/hr for a session actually running
		// at 20,348 - its snapshot is scoped to its own much longer session.
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.MINING);
		s.addXp(Skill.MINING, 130);
		s.recordAction();
		s.recordAction();
		s.setActiveSeconds(23);

		assertEquals(20_348, Math.round(s.getXpPerHour()));
		assertEquals(313, Math.round(s.getActionsPerHour()));
	}

	@Test
	public void ratesAreZeroRatherThanInfiniteBeforeAnyTimeHasPassed()
	{
		ActivitySession s = new ActivitySession();
		s.setSkill(Skill.MINING);
		s.addXp(Skill.MINING, 130);
		assertEquals(0, Math.round(s.getXpPerHour()));
		assertEquals(0, Math.round(s.getActionsPerHour()));
	}

	@Test
	public void aSessionWithNoProjectionReadsAsEmptyNotNull()
	{
		// pre-v2 records have no projection recorded at all
		assertTrue(new ActivitySession().getProjection().isEmpty());
	}
}
