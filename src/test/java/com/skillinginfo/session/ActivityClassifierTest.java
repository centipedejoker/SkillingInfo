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
		// Mining isn't covered yet - §15 requires it still tracks fine,
		// just without a named method
		ActivitySession s = session(Skill.MINING);
		s.addGenerated(ItemID.IRON_ORE, 30);

		assertEquals(ActivityClassifier.UNCLASSIFIED, ActivityClassifier.classify(s));
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
		assertEquals("Produced", ActivityClassifier.outputNoun(Skill.MINING));
	}
}
