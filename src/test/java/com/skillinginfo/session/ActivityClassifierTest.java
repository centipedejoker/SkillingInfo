package com.skillinginfo.session;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Covers SPEC.md §16's classification rules, including the ones that are
 * about restraint: an unrecognised output, or a skill with no table, must
 * stay Unclassified rather than being guessed at (§15/§16).
 */
public class ActivityClassifierTest
{
	private static ActivitySession session(Skill skill)
	{
		ActivitySession s = new ActivitySession();
		s.setSkill(skill);
		return s;
	}

	@Test
	public void namesTheActivityFromWhatWasProduced()
	{
		ActivitySession s = session(Skill.WOODCUTTING);
		s.addGenerated(ItemID.OAK_LOGS, 12);

		assertEquals("Oak trees", ActivityClassifier.classify(s));
	}

	@Test
	public void dominantOutputWinsSoByCatchDoesNotRename()
	{
		// bird nests and stray drops shouldn't be able to rename a session
		ActivitySession s = session(Skill.WOODCUTTING);
		s.addGenerated(ItemID.YEW_LOGS, 40);
		s.addGenerated(ItemID.OAK_LOGS, 2);

		assertEquals("Yew trees", ActivityClassifier.classify(s));
	}

	@Test
	public void severalItemsFromOneMethodClassifyTogether()
	{
		// fly fishing yields both, and either way it's still fly fishing
		ActivitySession s = session(Skill.FISHING);
		s.addGenerated(ItemID.RAW_TROUT, 20);
		s.addGenerated(ItemID.RAW_SALMON, 14);

		assertEquals("Fly fishing", ActivityClassifier.classify(s));
	}

	@Test
	public void groundPickupsDoNotIdentifyTheMethod()
	{
		// picking logs up off the floor is not evidence you were chopping
		ActivitySession s = session(Skill.FISHING);
		s.addPickedUp(ItemID.OAK_LOGS, 50);

		assertEquals(ActivityClassifier.UNCLASSIFIED, ActivityClassifier.classify(s));
	}

	@Test
	public void unrecognisedOutputStaysUnclassified()
	{
		ActivitySession s = session(Skill.WOODCUTTING);
		s.addGenerated(ItemID.IRON_ORE, 30);

		assertEquals(ActivityClassifier.UNCLASSIFIED, ActivityClassifier.classify(s));
	}

	@Test
	public void skillWithoutATableStaysUnclassified()
	{
		// Agility produces nothing that identifies a course, so it can't be
		// classified from item output at all - §15 requires it still tracks
		// fine, just without a named method. Deliberately not a skill that
		// might later gain a table, so this keeps testing the invariant.
		ActivitySession s = session(Skill.AGILITY);
		s.addGenerated(ItemID.IRON_ORE, 30);

		assertEquals(ActivityClassifier.UNCLASSIFIED, ActivityClassifier.classify(s));
	}

	@Test
	public void coversTheGatheringSkillsAdded()
	{
		ActivitySession mining = session(Skill.MINING);
		mining.addGenerated(ItemID.RUNITE_ORE, 8);
		assertEquals("Runite ore", ActivityClassifier.classify(mining));

		ActivitySession hunter = session(Skill.HUNTER);
		hunter.addGenerated(ItemID.CHINCHOMPA_BLACK, 200);
		assertEquals("Black chinchompas", ActivityClassifier.classify(hunter));

		ActivitySession farming = session(Skill.FARMING);
		farming.addGenerated(ItemID.UNIDENTIFIED_RANARR, 30);
		assertEquals("Ranarr", ActivityClassifier.classify(farming));
	}

	@Test
	public void bothEssenceTypesShareOneActivityName()
	{
		ActivitySession s = session(Skill.MINING);
		s.addGenerated(ItemID.BLANKRUNE, 100);
		s.addGenerated(ItemID.BLANKRUNE_HIGH, 40);

		assertEquals("Essence", ActivityClassifier.classify(s));
	}

	@Test
	public void emptySessionIsUnclassified()
	{
		assertEquals(ActivityClassifier.UNCLASSIFIED, ActivityClassifier.classify(session(Skill.FISHING)));
	}

	@Test
	public void outputNounMatchesTheSkill()
	{
		assertEquals("Logs", ActivityClassifier.outputNoun(Skill.WOODCUTTING));
		assertEquals("Catches", ActivityClassifier.outputNoun(Skill.FISHING));
		assertEquals("Ores", ActivityClassifier.outputNoun(Skill.MINING));
		assertEquals("Harvested", ActivityClassifier.outputNoun(Skill.FARMING));
		// uncovered skills fall back rather than showing a wrong noun
		assertEquals("Produced", ActivityClassifier.outputNoun(Skill.AGILITY));
	}
}
