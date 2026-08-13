package com.skillinginfo.session;

import lombok.Getter;

/**
 * One item's lifecycle counters within a session (SPEC.md §17/§18/§44).
 * `directlyAcquired` is the umbrella "entered inventory this session"
 * total across every channel (direct skilling output, ground pickup) -
 * `generated` stays purely informational (what the activity itself
 * produced, whether picked up or not). Net retained is driven off
 * `directlyAcquired`, not `generated`, so a ground pickup with no
 * matching same-tick XP still counts correctly. `consumed` lights up
 * once §18's ITEM_CONSUMED path exists; `banked` is populated by Phase
 * 4's bank correlator (§25a).
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

	/**
	 * SPEC.md §18/§50 `[v9]`: left the inventory, no explanation, and the
	 * activity has no inputs it could have been (§18 `[v9]`). Coal bags, herb
	 * sacks, gem sacks and the Motherlode hopper all land here - they have no
	 * client-side container, so the item genuinely vanishes as far as the
	 * plugin can see.
	 * <p>
	 * Deliberately absent from {@link #getNetRetained()}: the coal is in the
	 * bag, not destroyed, and booking it as consumption made a full 27-coal
	 * trip report `0.0% RETAINED`. §50 keeps it off the screen; the point is
	 * that it stops corrupting the figures that are on the screen.
	 */
	private int otherLoss;

	// SPEC.md §20b: skilling output is unambiguously the session's own,
	// there's no shared-tile provenance question the way there is for
	// combat loot picked off the ground - PER_DROP by default.
	private AttributionConfidence attributionConfidence = AttributionConfidence.PER_DROP;

	ItemFlowEntry(int itemId)
	{
		this.itemId = itemId;
	}

	/**
	 * Skilling output: produced and, by the nature of gathering, already in
	 * the inventory - so it counts as acquired in the same step.
	 */
	void addGenerated(int qty)
	{
		generated += qty;
		directlyAcquired += qty;
	}

	/**
	 * Combat loot: produced by the activity but lying on the floor, which is
	 * not the same as having it (§17). It only becomes acquired if the
	 * pickup correlator (§20a) later confirms it was taken - which is exactly
	 * the gap between gross loot and account gain the plugin exists to show.
	 */
	void addGeneratedOnly(int qty)
	{
		generated += qty;
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
	 * SPEC.md §18: used up by the activity - cooked, smelted, fletched,
	 * eaten, fired. Not a drop (still exists, on the floor) and not a
	 * deposit (still exists, in the bank): consumed items are genuinely
	 * gone, so they reduce net retained.
	 */
	void addConsumed(int qty)
	{
		consumed += qty;
	}

	/** SPEC.md §25a: confirmed moved from inventory into the bank this session. */
	void addBanked(int qty)
	{
		banked += qty;
	}

	/** SPEC.md §18/§50 `[v9]`: gone, with no explanation and no input it could have been. */
	void addOtherLoss(int qty)
	{
		otherLoss += qty;
	}

	/**
	 * SPEC.md §28: net retained = acquired - discards - consumption ±
	 * repickup. Banking deliberately does NOT reduce this - a banked item
	 * is still retained, and per §33 it's the *strongest* form of account
	 * gain, not a loss. Consumption does reduce it: those items are gone.
	 * Clamped at zero because a session can legitimately consume items it
	 * never acquired (cooking a stack you already had).
	 * <p>
	 * `[v9]` {@code otherLoss} is deliberately not subtracted. An
	 * unexplained disappearance is not evidence of destruction, and treating
	 * it as such is what made a banked coal-bag trip read `0.0% RETAINED`.
	 */
	public int getNetRetained()
	{
		return Math.max(0, directlyAcquired - dropped + repicked - consumed);
	}

	/**
	 * SPEC.md §25a step 1 - the "acquired this session, not yet resolved"
	 * ledger the three-way minimum caps against: what the session brought
	 * in and still holds in inventory, i.e. not yet banked.
	 */
	public int getOutstandingForBanking()
	{
		return Math.max(0, getNetRetained() - banked);
	}
}
