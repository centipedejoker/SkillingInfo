package com.skillinginfo.session;

import com.skillinginfo.SkillingInfoConfig;
import java.util.List;
import java.util.Optional;
import net.runelite.api.Skill;

/**
 * Pure confidence-gate logic for turning a CandidateBuffer into a prompt
 * (SPEC.md §8). Deliberately simple and deterministic per §8's own guidance
 * ("avoid over-engineered detection") - tune the config defaults through
 * real play rather than adding statistical machinery here.
 */
public final class CandidateDetector
{
	private static final double TICK_SECONDS = 0.6;

	private CandidateDetector()
	{
	}

	public static Optional<PromptSummary> evaluate(Skill skill, CandidateBuffer buffer, SkillingInfoConfig config)
	{
		List<QualifyingXpEvent> events = buffer.events();
		if (events.size() < config.candidateMinDrops())
		{
			return Optional.empty();
		}

		QualifyingXpEvent first = events.get(0);
		QualifyingXpEvent last = events.get(events.size() - 1);

		int windowTicks = secondsToTicks(config.candidateWindowSeconds());
		if (last.getTick() - first.getTick() > windowTicks)
		{
			return Optional.empty();
		}

		int minSpacingTicks = secondsToTicks(config.minSpacingSeconds());
		for (int i = 1; i < events.size(); i++)
		{
			int gap = events.get(i).getTick() - events.get(i - 1).getTick();
			if (gap < minSpacingTicks)
			{
				// too bursty to be repeated player actions - likely a
				// reward/message burst that slipped past the same-tick
				// filter in SessionManager (SPEC.md §9)
				return Optional.empty();
			}
		}

		int totalXp = events.stream().mapToInt(QualifyingXpEvent::getXpDelta).sum();
		long elapsedSeconds = ticksToSeconds(last.getTick() - first.getTick());

		return Optional.of(new PromptSummary(skill, events.size(), elapsedSeconds, totalXp));
	}

	static int secondsToTicks(int seconds)
	{
		return (int) Math.round(seconds / TICK_SECONDS);
	}

	static long ticksToSeconds(int ticks)
	{
		return Math.round(ticks * TICK_SECONDS);
	}
}
