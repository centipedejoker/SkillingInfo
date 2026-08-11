package com.skillinginfo.ui;

import com.skillinginfo.session.ItemFlowEntry;

/**
 * One shared formatter for an item-flow line, so the live view and history
 * can't drift apart (they already did once, when history silently omitted
 * item flow entirely).
 * <p>
 * Deliberately one line per item, net retained only - the
 * generated/dropped/picked-up breakdown is backlog for the expandable
 * detail view (SPEC.md §65), and the sidebar is too narrow for it.
 * Confirmed-banked is the exception that earns its space: it's the
 * strongest account-gain state (§33) and the plugin's headline claim
 * (§2/§63), so it's surfaced whenever it's nonzero.
 */
final class ItemFlowFormat
{
	private ItemFlowFormat()
	{
	}

	static String line(String itemName, ItemFlowEntry entry)
	{
		String base = String.format("%s  +%,d", itemName, entry.getNetRetained());
		if (entry.getBanked() > 0)
		{
			base += String.format("  (%,d banked)", entry.getBanked());
		}
		return base;
	}
}
