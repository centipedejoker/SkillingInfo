package com.skillinginfo.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Skill;

/**
 * The single ActivitySession model shared by live UI, history and
 * persistence (SPEC.md §51). Phase 1 only populates the performance fields
 * (§61); activity classification defaults to "Unclassified" (§15/§16) and
 * item-flow fields are added in Phase 2+.
 * <p>
 * {@code skill} is a *tracking-group key* (SPEC.md §7a), not necessarily the
 * literal skill that triggered detection: for a skilling session it's the
 * skill itself, but for a combat session it's always {@code SLAYER} (§1a
 * scopes combat sessions to Slayer-task combat only). The real per-skill
 * split - Attack/Strength/Ranged/Magic/Hitpoints/Slayer - still lives in
 * {@code xpGained}; nothing is discarded, {@code skill} just drives session
 * identity, detection, and the headline rate (§14).
 * <p>
 * {@code tripBoundaries} is intentionally present but unused until Phase 6
 * (§39 [v2]) - pre-wiring it now avoids a schema migration later, since a
 * bank-open while ACTIVE is already a free byproduct of the bank correlator
 * added in Phase 4.
 */
@Getter
public class ActivitySession
{
	private final String id = UUID.randomUUID().toString();
	private final int schemaVersion = 1;

	@Setter
	private Skill skill;

	@Setter
	private String activity = "Unclassified";

	@Setter
	private Instant startedAt;

	@Setter
	private Instant endedAt;

	@Setter
	private long totalSeconds;

	@Setter
	private long activeSeconds;

	@Setter
	private long idleSeconds;

	private final List<Instant> tripBoundaries = new ArrayList<>();

	private final Map<Skill, Integer> xpGained = new EnumMap<>(Skill.class);

	/** SPEC.md §1a/§7a: combat sessions are always the SLAYER group key. */
	public String getCategory()
	{
		return TrackingGroups.isCombatGroup(skill) ? "COMBAT" : "SKILLING";
	}

	public void addXp(Skill skill, int delta)
	{
		if (delta <= 0)
		{
			return;
		}
		xpGained.merge(skill, delta, Integer::sum);
	}

	public int getXpGained(Skill skill)
	{
		return xpGained.getOrDefault(skill, 0);
	}

	public void addTripBoundary(Instant when)
	{
		tripBoundaries.add(when);
	}
}
