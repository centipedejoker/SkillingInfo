package com.skillinginfo.session;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Tracks per-skill total XP so StatChanged (which reports a running total,
 * not a delta) can be turned into a delta, the same way XP Tracker does
 * (SPEC.md §5). The first observation of a skill after (re)login is not
 * counted as a gain - it's just the initial sync.
 */
public class XpTracker
{
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);

	/**
	 * @return the positive XP gained since the last observation of this
	 * skill, or 0 if this is the first observation or XP did not increase.
	 */
	public int pollDelta(Skill skill, int currentTotalXp)
	{
		Integer previous = lastXp.put(skill, currentTotalXp);
		if (previous == null)
		{
			return 0;
		}
		return Math.max(currentTotalXp - previous, 0);
	}

	public void reset()
	{
		lastXp.clear();
	}
}
