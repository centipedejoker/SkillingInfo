package com.skillinginfo.session;

import lombok.Getter;

/**
 * One item's lifecycle counters within a session (SPEC.md §17/§18/§44).
 * `directlyAcquired` is the umbrella "entered inventory this session"
 * total across every channel (direct skilling output, ground pickup) -
 * `generated` stays purely informational (what the activity itself
 * produced, whether picked up or not). Net retained is driven off
 * `directlyAcquired`, not `generated`, so a ground pickup with no
 * matching same-tick XP still counts correctly. banked/consumed light
 * up in Phase 4 as its correlator (§25a) comes online.
 */
@Getter
public class ItemFlowEntry
{
	private final int itemId;
	private int generated;
	private int directlyAcquired;
	private int pickedUp;
	private int dropped;
	private int repicked;
	private int consumed;
	private int banked;

	// SPEC.md §20b: skilling output is unambiguously the session's own,
	// there's no shared-tile provenance question the way there is for
	// combat loot picked off the ground - PER_DROP by default.
	private AttributionConfidence attributionConfidence = AttributionConfidence.PER_DROP;

	ItemFlowEntry(int itemId)
	{
		this.itemId = itemId;
	}

	void addGenerated(int qty)
	{
		generated += qty;
		directlyAcquired += qty;
	}

	void addDropped(int qty)
	{
		dropped += qty;
	}

	/** SPEC.md §20a/§21: a confirmed ground pickup that isn't a repickup of this session's own drop. */
	void addPickedUp(int qty)
	{
		pickedUp += qty;
		directlyAcquired += qty;
	}

	/** SPEC.md §22: reacquiring an item this session already dropped - not a new net gain. */
	void addRepicked(int qty)
	{
		repicked += qty;
	}

	/**
	 * SPEC.md §28: net retained = acquired - discards - consumption ±
	 * repickup, with confirmed banked preferred once available. banked/
	 * consumed are always zero until Phase 4's bank correlator exists, so
	 * this currently reduces to directlyAcquired - dropped + repicked.
	 */
	public int getNetRetained()
	{
		return Math.max(0, directlyAcquired - dropped + repicked - consumed);
	}
}
