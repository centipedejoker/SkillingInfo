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

		// SPEC.md §8's anti-burst guard, as a check on the buffer's total
		// span rather than on every consecutive pair.
		//
		// The per-pair version required a fixed gap between *every* two
		// drops, which broke detection outright for any fast method: mining
		// or fishing produces output every 2-3 ticks, and a single pair
		// closer than the threshold discarded the entire buffer. Because
		// fresh fast pairs kept arriving before old ones aged out, a
		// power-mining session could sit in CANDIDATE indefinitely and never
		// prompt.
		//
		// §8's actual intent is only to "reject a burst that lands in one or
		// two ticks" - a reward arriving all at once. Requiring the buffer to
		// span more than that says exactly this and nothing more, and it
		// can't be poisoned by one fast pair.
		int minSpanTicks = secondsToTicks(config.minSpanSeconds());
		if (last.getTick() - first.getTick() < minSpanTicks)
		{
			return Optional.empty();
		}

		return Optional.of(summarise(skill, buffer));
	}

	/**
	 * `[v9]` The buffer as the prompt describes it, with no gate applied.
	 * <p>
	 * Split out because the offer has to keep pace with what accepting it
	 * would actually record. The buffer goes on filling while the prompt is
	 * up (§10 `[v9]`), so a summary frozen at the moment the gate was met
	 * would advertise one number and hand you another the instant you
	 * pressed Start - which is the same defect as §14 `[v9]`, one screen
	 * earlier.
	 */
	static PromptSummary summarise(Skill skill, CandidateBuffer buffer)
	{
		List<QualifyingXpEvent> events = buffer.events();
		int totalXp = events.stream().mapToInt(QualifyingXpEvent::getXpDelta).sum();
		long elapsedSeconds = ticksToSeconds(
			events.get(events.size() - 1).getTick() - events.get(0).getTick());

		return new PromptSummary(skill, events.size(), elapsedSeconds, totalXp);
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
