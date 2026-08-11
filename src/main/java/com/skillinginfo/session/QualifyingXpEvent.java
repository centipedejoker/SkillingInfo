package com.skillinginfo.session;

import lombok.Getter;
import net.runelite.api.Skill;

/**
 * One buffered candidate XP drop (SPEC.md §10). Time is tracked in game
 * ticks rather than wall-clock so window/spacing checks stay deterministic.
 */
@Getter
public class QualifyingXpEvent
{
	private final Skill skill;
	private final int xpDelta;
	private final int tick;

	public QualifyingXpEvent(Skill skill, int xpDelta, int tick)
	{
		this.skill = skill;
		this.xpDelta = xpDelta;
		this.tick = tick;
	}
}
