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
		keyName = "minSpacingSeconds",
		name = "Minimum spacing (seconds)",
		description = "Minimum seconds between consecutive drops; rejects reward bursts that land in one or two ticks (spec §8, §9)."
	)
	default int minSpacingSeconds()
	{
		return 2;
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

	// ------------------------------------------------------------------
	// Future XP (spec §33/§35). Items with more than one legitimate use
	// are configurable rather than assumed - §35 explicitly forbids
	// forcing a downstream mapping on an ambiguous item.
	// ------------------------------------------------------------------

	enum LogsUse
	{
		FIREMAKING,
		OFF
	}

	@ConfigItem(
		keyName = "logsFutureXp",
		name = "Logs future XP",
		description = "What to assume banked logs will be used for. Fletching and Construction are deliberately absent - neither has a single XP value per log (it depends what you make), so projecting one would be a guess dressed up as data (spec §35)."
	)
	default LogsUse logsFutureXp()
	{
		return LogsUse.FIREMAKING;
	}

	enum BonesUse
	{
		BURY,
		GILDED_ALTAR,
		OFF
	}

	@ConfigItem(
		keyName = "bonesFutureXp",
		name = "Bones future XP",
		description = "What to assume banked bones will be used for. Gilded altar assumes both burners lit (350% of bury XP)."
	)
	default BonesUse bonesFutureXp()
	{
		return BonesUse.BURY;
	}
}
