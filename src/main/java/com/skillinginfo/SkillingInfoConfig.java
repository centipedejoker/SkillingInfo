package com.skillinginfo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("skillinginfo")
public interface SkillingInfoConfig extends Config
{
	@ConfigItem(
		keyName = "candidateMinDrops",
		name = "Minimum XP drops",
		description = "Same-skill XP drops required before a session is suggested (spec §8: ~3-5)."
	)
	default int candidateMinDrops()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "candidateWindowSeconds",
		name = "Detection window (seconds)",
		description = "The qualifying XP drops must all fall within this many seconds (spec §8: ~60-90)."
	)
	default int candidateWindowSeconds()
	{
		return 90;
	}

	@ConfigItem(
		keyName = "minSpanSeconds",
		name = "Minimum span (seconds)",
		description = "The qualifying XP drops must span at least this long in total, which rejects a reward burst arriving all at once (spec §8). Deliberately small: this is an anti-burst guard, not a speed limit - checking the gap between every pair instead made fast methods like power-mining undetectable."
	)
	default int minSpanSeconds()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "promptTimeoutSeconds",
		name = "Prompt timeout (seconds)",
		description = "How long the Start/Ignore prompt stays up before it auto-expires (spec §7)."
	)
	default int promptTimeoutSeconds()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "idleThresholdSeconds",
		name = "Idle threshold (seconds)",
		description = "Inactivity duration after which an active session auto-pauses (spec §13)."
	)
	default int idleThresholdSeconds()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "suppressionCooldownSeconds",
		name = "Ignore cooldown (seconds)",
		description = "How long detection stays suppressed for a skill after choosing Ignore, before it can prompt again (spec §7 [v2])."
	)
	default int suppressionCooldownSeconds()
	{
		return 600;
	}
}
