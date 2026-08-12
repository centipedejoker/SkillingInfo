package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.util.Optional;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers SPEC.md §8's confidence gate.
 * <p>
 * The fast-method cases exist because of a real failure: the gate
 * originally required a fixed gap between every consecutive pair of XP
 * drops, which meant a power-mining session - output every 2-3 ticks -
 * could sit in CANDIDATE forever and never prompt. Nothing surfaced it
 * except the session simply never starting.
 */
public class CandidateDetectorTest
{
	/** All config methods have defaults, so the interface stands in as-is. */
	private static final SkillingInfoConfig CONFIG = new SkillingInfoConfig()
	{
	};

	private static CandidateBuffer buffer(int... ticks)
	{
		CandidateBuffer b = new CandidateBuffer();
		for (int tick : ticks)
		{
			b.add(new QualifyingXpEvent(Skill.MINING, 35, tick));
		}
		return b;
	}

	private static boolean gateMet(int... ticks)
	{
		Optional<PromptSummary> result =
			CandidateDetector.evaluate(Skill.MINING, buffer(ticks), CONFIG);
		return result.isPresent();
	}

	@Test
	public void steadySkillingPasses()
	{
		assertTrue(gateMet(0, 5, 10, 15));
	}

	@Test
	public void fastMethodsPass()
	{
		// power-mining: an ore every 2-3 ticks. This is the case the old
		// per-pair gate rejected outright.
		assertTrue("2-tick cadence must be detectable", gateMet(0, 2, 4, 6));
		assertTrue("3-tick cadence must be detectable", gateMet(0, 3, 6, 9));
	}

	@Test
	public void uneveCadenceIsNotPunished()
	{
		// one quick pair among slower ones must not discard the whole buffer,
		// which is precisely how the old gate failed
		assertTrue(gateMet(0, 1, 8, 16));
	}

	@Test
	public void sameTickBurstIsRejected()
	{
		// a reward arriving all at once - §8's actual target
		assertFalse(gateMet(0, 0, 0, 0));
	}

	@Test
	public void adjacentTickBurstIsRejected()
	{
		assertFalse("a burst landing within one tick is not repeated actions", gateMet(4, 4, 5));
	}

	@Test
	public void tooFewDropsIsRejected()
	{
		assertFalse(gateMet(0, 5));
	}

	@Test
	public void dropsSpreadBeyondTheWindowAreRejected()
	{
		// default window is 90s ≈ 150 ticks
		assertFalse(gateMet(0, 80, 400));
	}
}
