package com.skillinginfo.session;

import lombok.Getter;

/**
 * One item's lifecycle counters within a session (SPEC.md §17/§18/§44).
 * Phase 2 only populates generated/directlyAcquired/dropped - pickedUp,
 * repicked, banked, and consumed light up in Phases 3/4 as their
 * correlators (§20a, §25a) come online.
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

	/**
	 * SPEC.md §28: net retained = acquired - discards - consumption ±
	 * repickup, with confirmed banked preferred once available. Phase 2
	 * only tracks generated/dropped, so repicked/consumed/banked are
	 * always zero for now and this reduces to generated - dropped.
	 */
	public int getNetRetained()
	{
		return Math.max(0, generated - dropped + repicked - consumed);
	}
}
