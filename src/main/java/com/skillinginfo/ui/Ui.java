package com.skillinginfo.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.FontManager;

/**
 * Small building blocks shared by the panel's views, so the design's
 * repeated primitives (section bands, stat tiles, bars, rules) are defined
 * once rather than re-styled inline at every call site.
 * <p>
 * Two Swing details these helpers exist to hide:
 * <ul>
 * <li>Children of a vertical {@code BoxLayout} must share an X alignment or
 * they visibly stagger. Everything here is LEFT_ALIGNMENT.</li>
 * <li>Heights must never be measured at construction time - see
 * {@link #fixHeight}. Fixed-size pieces state their height as a number;
 * everything else is left to size itself from its content.</li>
 * </ul>
 */
final class Ui
{
	/** 225px panel less RuneLite's 6px padding each side. */
	static final int CONTENT_WIDTH = 213;

	private Ui()
	{
	}

	static JLabel label(String text, Font font, Color colour)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(colour);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	static JLabel body(String text)
	{
		return label(text, FontManager.getRunescapeSmallFont(), Palette.TEXT);
	}

	static JLabel dim(String text)
	{
		return label(text, FontManager.getRunescapeSmallFont(), Palette.DIM);
	}

	static JLabel bold(String text, Color colour)
	{
		return label(text, FontManager.getRunescapeBoldFont(), colour);
	}

	/**
	 * A section band: small caps label on a filled strip. The design uses
	 * these instead of rules to open each block (OUTPUT, SESSION, ITEM FLOW).
	 */
	static JPanel band(String text)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(Palette.TILE);
		p.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
		JLabel l = label(text, FontManager.getRunescapeSmallFont(), Palette.DIM);
		p.add(l, BorderLayout.WEST);
		return fixHeight(p);
	}

	/**
	 * One stat tile - caption above value. Paired two-up these fit where the
	 * old label/value list fit one row, which is where most of the vertical
	 * saving in the redesign comes from.
	 */
	static JPanel tile(String caption, JLabel valueLabel, boolean paused)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(paused ? Palette.TILE_PAUSED : Palette.TILE);
		p.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));

		JLabel c = label(caption, FontManager.getRunescapeSmallFont(), paused ? Palette.DIMMEST : Palette.DIM);
		valueLabel.setFont(FontManager.getRunescapeBoldFont());
		valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		p.add(c);
		p.add(valueLabel);
		return p;
	}

	/** Two tiles side by side, 3px apart, filling the content width. */
	static JPanel tileRow(JPanel left, JPanel right)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 0));
		row.setOpaque(false);
		row.add(left);
		row.add(right);
		return fixHeight(row);
	}

	/** Vertical spacer that can't be stretched by the layout. */
	static Component gap(int height)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		Dimension d = new Dimension(1, height);
		p.setPreferredSize(d);
		p.setMinimumSize(d);
		p.setMaximumSize(d);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** 1px horizontal rule. */
	static Component rule(Color colour)
	{
		JPanel p = new JPanel();
		p.setBackground(colour);
		Dimension d = new Dimension(CONTENT_WIDTH, 1);
		p.setPreferredSize(d);
		p.setMinimumSize(d);
		p.setMaximumSize(d);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/**
	 * Left-aligns a row for a vertical {@code BoxLayout}.
	 * <p>
	 * This deliberately does <b>not</b> pin a maximum height. An earlier
	 * version snapshotted {@code getPreferredSize()} here and set that as the
	 * maximum, which clipped every multi-line row to roughly one line: at
	 * construction time the labels inside are still empty, so the snapshot
	 * measured empty text and then froze that height permanently, leaving the
	 * real values nowhere to render.
	 * <p>
	 * The pinning was also unnecessary. Every column in this panel is added to
	 * a {@code BorderLayout.NORTH}, which sizes it to its preferred height, so
	 * there is no leftover vertical space for {@code BoxLayout} to stretch a
	 * row into. Components that genuinely have a fixed height - {@link #gap},
	 * {@link #rule}, {@link Bar} - set their own bounds explicitly from real
	 * numbers rather than from a measurement.
	 */
	static <T extends JComponent> T fixHeight(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	/**
	 * Flat two-tone bar: a filled proportion against a recessed track, with
	 * an optional second segment for the remainder. Square, 1px-bordered, no
	 * gradient - Swing's own progress bar can't be made to look like this and
	 * brings a look-and-feel dependency with it.
	 */
	static class Bar extends JComponent
	{
		private double fraction;
		private Color fill = Palette.ACCENT;
		private Color remainder;
		private Color border = Palette.BORDER;

		Bar(int height)
		{
			Dimension d = new Dimension(CONTENT_WIDTH, height);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		void set(double fraction, Color fill, Color remainder, Color border)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
			this.fill = fill;
			this.remainder = remainder;
			this.border = border;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int w = getWidth();
			int h = getHeight();

			g.setColor(Palette.WELL);
			g.fillRect(0, 0, w, h);

			int filled = (int) Math.round((w - 2) * fraction);
			g.setColor(fill);
			g.fillRect(1, 1, filled, h - 2);

			if (remainder != null)
			{
				g.setColor(remainder);
				g.fillRect(1 + filled, 1, (w - 2) - filled, h - 2);
			}

			g.setColor(border);
			g.drawRect(0, 0, w - 1, h - 1);
		}
	}

	/**
	 * Right-aligned value label for the ledger rows in the expanded session
	 * detail, where the numbers need to line up as arithmetic.
	 */
	static JPanel ledgerRow(String name, String value, Color valueColour, boolean boldRow)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		JLabel n = boldRow
			? label(name, FontManager.getRunescapeBoldFont(), Palette.TEXT)
			: label(name, FontManager.getRunescapeSmallFont(), Palette.DIM);
		JLabel v = boldRow
			? label(value, FontManager.getRunescapeBoldFont(), valueColour)
			: label(value, FontManager.getRunescapeSmallFont(), valueColour);
		v.setHorizontalAlignment(SwingConstants.RIGHT);

		p.add(n, BorderLayout.WEST);
		p.add(v, BorderLayout.EAST);
		return fixHeight(p);
	}
}
