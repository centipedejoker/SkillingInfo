package com.skillinginfo.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Skill;

/**
 * The single ActivitySession model shared by live UI, history and
 * persistence (SPEC.md §51). Phase 1 populated the performance fields
 * (§61); item-flow (§17/§18) is added in Phase 2, starting with
 * generated/directly-acquired/dropped - pickedUp/repicked/banked/consumed
 * light up as their correlators (§20a, §25a) come online in later phases.
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
	// v2 adds the frozen projection below. Additive only, so v1 records
	// still load - they simply have no projection recorded.
	private final int schemaVersion = 2;

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

	/**
	 * Qualifying XP drops credited to this session - one per action.
	 * Counted here rather than read from XP Tracker because that plugin's
	 * figures are scoped to *its* session, not ours (§14 [v7]). Works for
	 * skills that produce no items at all, which is what made an external
	 * action count attractive in the first place.
	 */
	private int actions;

	/** Kills credited to this session (§37), not to the task as a whole. */
	private int kills;

	private final Map<Integer, ItemFlowEntry> itemFlow = new LinkedHashMap<>();

	/**
	 * Projected XP as resolved when this session ended (§33). Never
	 * recomputed afterwards - see {@link ProjectedXp}. Null on records
	 * written before schemaVersion 2.
	 */
	@Setter
	private List<ProjectedXp> projection;

	/** §17/§37: loot a monster dropped, whether or not it was picked up. */
	public void addGeneratedOnly(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addGeneratedOnly(qty);
	}

	public void addGenerated(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addGenerated(qty);
	}

	public void addDropped(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addDropped(qty);
	}

	public void addPickedUp(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addPickedUp(qty);
	}

	public void addRepicked(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addRepicked(qty);
	}

	public void addConsumed(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addConsumed(qty);
	}

	public void addBanked(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addBanked(qty);
	}

	/** SPEC.md §18/§50 `[v9]`: gone with no explanation - never shown, never netted off. */
	public void addOtherLoss(int itemId, int qty)
	{
		if (qty <= 0)
		{
			return;
		}
		itemFlow.computeIfAbsent(itemId, ItemFlowEntry::new).addOtherLoss(qty);
	}

	/** SPEC.md §22: how much of this item is still "dropped but not yet repicked" - the repickup offset. */
	public int getOutstandingDropped(int itemId)
	{
		ItemFlowEntry entry = itemFlow.get(itemId);
		return entry == null ? 0 : Math.max(0, entry.getDropped() - entry.getRepicked());
	}

	/** SPEC.md §25a step 1: acquired this session and still held unbanked - the bank correlation ledger. */
	public int getOutstandingForBanking(int itemId)
	{
		ItemFlowEntry entry = itemFlow.get(itemId);
		return entry == null ? 0 : entry.getOutstandingForBanking();
	}

	/** SPEC.md §40: total units produced by the activity - "logs", "catches". */
	public int getTotalGenerated()
	{
		return itemFlow.values().stream().mapToInt(ItemFlowEntry::getGenerated).sum();
	}

	public int getTotalNetRetained()
	{
		return itemFlow.values().stream().mapToInt(ItemFlowEntry::getNetRetained).sum();
	}

	public int getTotalConsumed()
	{
		return itemFlow.values().stream().mapToInt(ItemFlowEntry::getConsumed).sum();
	}

	public int getTotalBanked()
	{
		return itemFlow.values().stream().mapToInt(ItemFlowEntry::getBanked).sum();
	}

	/**
	 * SPEC.md §32: net retained / total generated. Returns -1 when nothing
	 * has been generated yet, so callers can hide the row rather than
	 * showing a meaningless 0% or dividing by zero.
	 */
	public double getRetentionRate()
	{
		int generated = getTotalGenerated();
		if (generated <= 0)
		{
			return -1;
		}
		// `[v9]` Capped at 1.0. The question this answers is "of what this
		// activity produced, how much did I keep", and there is no coherent
		// answer above all of it - yet net retained counts ground pickups
		// while the denominator doesn't, so picking up items the session
		// didn't produce pushed the headline over 100%. The bar was already
		// clamped, so the number beside it was simply disagreeing with it.
		return Math.min(1.0, getTotalNetRetained() / (double) generated);
	}

	/** Never null, so callers don't have to special-case pre-v2 records. */
	public List<ProjectedXp> getProjection()
	{
		return projection == null ? java.util.Collections.emptyList() : projection;
	}

	public Collection<ItemFlowEntry> getItemFlow()
	{
		return itemFlow.values();
	}

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

	public void recordAction()
	{
		actions++;
	}

	public void recordKills(int count)
	{
		if (count > 0)
		{
			kills += count;
		}
	}

	/** §37: kills per hour of active time. */
	public double getKillsPerHour()
	{
		return activeSeconds > 0 ? kills / (double) activeSeconds * 3600 : 0;
	}

	/**
	 * §14 `[v9]`: the XP figure the panel shows and rates off.
	 * <p>
	 * For a skilling session this is just the skill's own XP. For combat it
	 * is the sum across every skill the session gained, because
	 * {@code skill} there is the *group key* `SLAYER` (§7a) and a task pays
	 * Attack, Strength, Hitpoints and Slayer together.
	 * <p>
	 * Reading the group key's own total made the panel disagree with itself:
	 * the prompt sums every buffered drop, so it offered "+1,800 XP", you
	 * pressed Start, and the XP tile read `+200` - same panel, same events,
	 * nine times apart, with no change of label. `XP/HR`, the overall-rate
	 * overflow line and the history aggregate all inherited it, so a session
	 * genuinely running at 55k XP/hr displayed about 8k. That is §14's
	 * original "displayed 15 for a 20,348 XP/hr session" arrived at by a
	 * different route.
	 */
	public int getHeadlineXp()
	{
		if (!TrackingGroups.isCombatGroup(skill))
		{
			return getXpGained(skill);
		}
		return xpGained.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** §14: XP per hour of *active* time. */
	public double getXpPerHour()
	{
		return activeSeconds > 0 ? getHeadlineXp() / (double) activeSeconds * 3600 : 0;
	}

	public double getActionsPerHour()
	{
		return activeSeconds > 0 ? actions / (double) activeSeconds * 3600 : 0;
	}

	/** Total projected XP recorded for this session (§33), across all skills. */
	public double getProjectedXpTotal()
	{
		return getProjection().stream().mapToDouble(ProjectedXp::getXp).sum();
	}

	/**
	 * §39: records a bank visit as a trip boundary, ignoring repeats within
	 * the same visit - the bank container fires on every deposit, and thirty
	 * timestamps one second apart describe one trip, not thirty.
	 */
	public void recordBankVisit(Instant when)
	{
		if (!tripBoundaries.isEmpty())
		{
			Instant last = tripBoundaries.get(tripBoundaries.size() - 1);
			if (when.minusSeconds(30).isBefore(last))
			{
				return;
			}
		}
		tripBoundaries.add(when);
	}

	public void addTripBoundary(Instant when)
	{
		tripBoundaries.add(when);
	}
}
