package com.skillinginfo.session;

import lombok.Getter;
import net.runelite.api.Skill;

/**
 * Display data for the Start/Ignore prompt (SPEC.md §6).
 */
@Getter
public class PromptSummary
{
	private final Skill skill;
	private final int dropCount;
	private final long elapsedSeconds;
	private final int totalXp;

	public PromptSummary(Skill skill, int dropCount, long elapsedSeconds, int totalXp)
	{
		this.skill = skill;
		this.dropCount = dropCount;
		this.elapsedSeconds = elapsedSeconds;
		this.totalXp = totalXp;
	}
}
