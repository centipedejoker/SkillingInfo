package com.skillinginfo.ui;

import java.awt.Color;
import net.runelite.client.ui.ColorScheme;

/**
 * The design's colour tokens, in one place (SPEC.md §65).
 * <p>
 * §65 says never scatter hex literals through the UI, and this class is how
 * that rule is kept: every colour in the panel comes from here. Where a token
 * is exactly RuneLite's own it references {@link ColorScheme} directly;
 * where the design deliberately diverges the value is stated with the reason.
 * <p>
 * <b>Dark only.</b> The design supplies a light-theme palette, but RuneLite's
 * {@code ColorScheme} is a fixed set of dark constants with no theme switch to
 * hook into — there is nothing to respond to. The light values are recorded in
 * the design doc, and swapping them in is a change to this one file if
 * RuneLite ever gains theming.
 */
final class Palette
{
	/** Panel background. RuneLite's DARK_GRAY_COLOR is #282828, near-identical. */
	static final Color PANEL = ColorScheme.DARK_GRAY_COLOR;

	/** Tile and section-header fill - sits one step above the panel. */
	static final Color TILE = new Color(0x2E2E2E);

	/** Recessed well: progress-bar tracks and icon backings. Exactly ColorScheme's. */
	static final Color WELL = ColorScheme.DARKER_GRAY_COLOR;

	/** 1px borders. Exactly ColorScheme's DARKER_GRAY_HOVER_COLOR. */
	static final Color BORDER = ColorScheme.DARKER_GRAY_HOVER_COLOR;

	/** Hairline between list rows - lighter than BORDER so lists don't read as boxes. */
	static final Color ROW_RULE = new Color(0x303030);

	/** Primary text. Brighter than ColorScheme.TEXT_COLOR (#C6C6C6): the design
	 *  leans on contrast rather than colour to build hierarchy, so facts need
	 *  the extra step to stay clearly above dim labels. */
	static final Color TEXT = new Color(0xDCDCDC);

	/** Secondary text and section labels. */
	static final Color DIM = new Color(0x8F8F8F);

	/** Dimmest tier, reserved for projections (§33) - never used for facts. */
	static final Color DIMMEST = new Color(0x5E5E5E);

	/**
	 * The single accent. Brighter than ColorScheme.BRAND_ORANGE (#DC8A00)
	 * because it carries meaning here rather than decorating chrome.
	 * <p>
	 * Spent deliberately: this colour marks <em>what the account actually
	 * kept</em> and the primary action, and nothing else. It never appears on
	 * headers, borders, or projected figures - which is what stops a
	 * projection from ever reading as an earned fact.
	 */
	static final Color ACCENT = new Color(0xFF981F);

	/** Desaturated accent for the paused state - the bar stays legible without
	 *  looking live. */
	static final Color ACCENT_MUTED = new Color(0x6E5A3A);

	/** Panel fill while paused: one step down in contrast (§3.4 of the design). */
	static final Color TILE_PAUSED = new Color(0x2A2A2A);
	static final Color BORDER_PAUSED = new Color(0x363636);

	private Palette()
	{
	}
}
