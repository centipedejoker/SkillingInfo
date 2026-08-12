package com.skillinginfo.ui;

import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.ItemFlowEntry;
import com.skillinginfo.session.ProjectedXp;
import com.skillinginfo.session.ProjectionBuilder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Completed sessions for one skill (SPEC.md §43, §65), laid out to the
 * Phase 7 design.
 * <p>
 * Two things the design fixes here. A per-skill aggregate now answers "what
 * has this skill actually given me" before any individual session, and rows
 * collapse to two lines and one number - that number being <em>items kept</em>
 * rather than XP, so the list scans on the differentiator. The full lifecycle
 * ledger (§29), which the plugin has always tracked but never displayed,
 * finally has somewhere to live: the expanded row.
 */
class HistoryView extends JPanel
{
	private static final DateTimeFormatter DATE_FORMAT =
		DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter DATE_TIME_FORMAT =
		DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
	private static final int ITEM_ICON = 32;

	private final Map<Integer, String> itemNames;
	private final ItemManager itemManager;
	private final JPanel listPanel = new JPanel();

	/** Which sessions are expanded, by id, so it survives a rebuild. */
	private final Set<String> expanded = new HashSet<>();

	HistoryView(Map<Integer, String> itemNames, ItemManager itemManager)
	{
		this.itemNames = itemNames;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(Palette.PANEL);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(Palette.PANEL);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(Palette.PANEL);
		wrapper.add(listPanel, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getViewport().setBackground(Palette.PANEL);
		add(scroll, BorderLayout.CENTER);
	}

	void refresh(List<ActivitySession> history, Skill filterSkill)
	{
		listPanel.removeAll();

		List<ActivitySession> filtered = history.stream()
			.filter(s -> s.getSkill() == filterSkill)
			.collect(Collectors.toList());

		if (filtered.isEmpty())
		{
			JLabel empty = Ui.dim("No " + filterSkill.getName() + " sessions recorded yet.");
			empty.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
			listPanel.add(Ui.fixHeight(empty));
		}
		else
		{
			listPanel.add(buildAggregate(filtered, filterSkill));
			listPanel.add(buildCollectedSummary(filtered));
			listPanel.add(Ui.gap(6));
			for (ActivitySession session : filtered)
			{
				listPanel.add(buildRow(session));
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * The per-skill summary the design asks for: what this skill has given
	 * the account across every session, before any single one of them.
	 */
	private JPanel buildAggregate(List<ActivitySession> sessions, Skill skill)
	{
		long activeSeconds = 0;
		long totalXp = 0;
		int generated = 0;
		int kept = 0;
		int banked = 0;
		double projectedXp = 0;
		for (ActivitySession s : sessions)
		{
			activeSeconds += s.getActiveSeconds();
			totalXp += s.getXpGained(skill);
			generated += s.getTotalGenerated();
			kept += s.getTotalNetRetained();
			banked += s.getTotalBanked();
			projectedXp += s.getProjectedXpTotal();
		}

		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setBackground(Palette.TILE);
		block.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Palette.BORDER),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		block.add(Ui.label(skill.getName().toUpperCase() + " · ALL TIME",
			FontManager.getRunescapeSmallFont(), Palette.DIM));
		block.add(Ui.gap(5));

		JPanel stats = new JPanel(new GridLayout(1, 3, 3, 0));
		stats.setOpaque(false);
		stats.add(miniStat("ACTIVE", formatHours(activeSeconds), Palette.TEXT));
		stats.add(miniStat("XP", CurrentView.formatAbbreviated(totalXp), Palette.TEXT));
		// the accent carries the account gain, expressed as XP so it's
		// comparable across skills; the raw banked count is in the footer
		stats.add(miniStat("BANKED XP",
			projectedXp > 0 ? "+" + CurrentView.formatAbbreviated(projectedXp) : "-", Palette.ACCENT));
		block.add(Ui.fixHeight(stats));

		String retention = generated > 0
			? String.format(" · %.1f%% retained", kept * 100.0 / generated)
			: "";
		String bankedItems = banked > 0 ? String.format(" · %,d banked", banked) : "";
		JLabel footer = Ui.dim(sessions.size() + (sessions.size() == 1 ? " session" : " sessions")
			+ bankedItems + retention);
		footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		block.add(Ui.fixHeight(footer));

		return Ui.fixHeight(block);
	}

	/**
	 * A running per-item tally across every session for this skill - what
	 * the account has actually accumulated, rather than what any one trip
	 * produced.
	 * <p>
	 * Ordered by quantity so the staple output leads and incidental drops
	 * fall to the bottom. Banked is called out separately from the total
	 * only when the two differ: saying "8,204 · 8,204 banked" on every row
	 * is noise, but the gap between them matters when there is one, since
	 * banked is the confirmed account gain (§33) and the remainder is still
	 * sitting in an inventory somewhere.
	 */
	private JPanel buildCollectedSummary(List<ActivitySession> sessions)
	{
		Map<Integer, int[]> totals = new LinkedHashMap<>();
		for (ActivitySession session : sessions)
		{
			for (ItemFlowEntry entry : session.getItemFlow())
			{
				int retained = entry.getNetRetained();
				if (retained <= 0 && entry.getBanked() <= 0)
				{
					continue;
				}
				int[] running = totals.computeIfAbsent(entry.getItemId(), id -> new int[2]);
				running[0] += retained;
				running[1] += entry.getBanked();
			}
		}

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(Palette.PANEL);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (totals.isEmpty())
		{
			return panel;
		}

		panel.add(Ui.gap(6));
		panel.add(Ui.band("COLLECTED · ALL TIME"));

		totals.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
			.forEach(e -> panel.add(collectedRow(e.getKey(), e.getValue()[0], e.getValue()[1])));

		return panel;
	}

	private JPanel collectedRow(int itemId, int retained, int banked)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(Palette.PANEL);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.ROW_RULE),
			BorderFactory.createEmptyBorder(4, 2, 4, 2)));

		JLabel icon = new JLabel();
		Dimension d = new Dimension(ITEM_ICON, ITEM_ICON);
		icon.setPreferredSize(d);
		icon.setMinimumSize(d);
		icon.setOpaque(true);
		icon.setBackground(Palette.WELL);
		icon.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
		AsyncBufferedImage image = itemManager.getImage(itemId);
		if (image != null)
		{
			image.addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		text.add(Ui.body(itemNames.getOrDefault(itemId, "Item #" + itemId)));
		if (banked != retained)
		{
			text.add(Ui.label(String.format("%,d banked", banked),
				FontManager.getRunescapeSmallFont(), Palette.DIMMEST));
		}
		row.add(text, BorderLayout.CENTER);

		JPanel value = new JPanel(new BorderLayout());
		value.setOpaque(false);
		value.add(Ui.bold(String.format("%,d", retained), Palette.ACCENT), BorderLayout.EAST);
		row.add(value, BorderLayout.EAST);

		return Ui.fixHeight(row);
	}

	private JPanel miniStat(String caption, String value, Color valueColour)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.add(Ui.label(caption, FontManager.getRunescapeSmallFont(), Palette.DIM));
		p.add(Ui.bold(value, valueColour));
		return p;
	}

	/**
	 * A collapsed session is two lines and one number. Clicking anywhere on
	 * it toggles the lifecycle detail, which is the only place §29's
	 * generated/dropped/repicked/consumed/banked breakdown is ever shown.
	 */
	private JPanel buildRow(ActivitySession session)
	{
		boolean isOpen = expanded.contains(session.getId());

		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(Palette.PANEL);
		container.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel header = new JPanel(new BorderLayout(5, 0));
		header.setBackground(isOpen ? Palette.TILE : Palette.PANEL);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.ROW_RULE),
			BorderFactory.createEmptyBorder(6, 2, 6, 2)));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel chevron = Ui.label(isOpen ? "▾" : "▸",
			FontManager.getRunescapeSmallFont(), isOpen ? Palette.ACCENT : Palette.DIM);
		header.add(chevron, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		JPanel titleLine = new JPanel(new BorderLayout(4, 0));
		titleLine.setOpaque(false);
		JLabel title = isOpen ? Ui.bold(session.getActivity(), Palette.TEXT) : Ui.body(session.getActivity());
		titleLine.add(title, BorderLayout.CENTER);

		// The accent figure is the session's projected XP, not its item
		// count: XP is the comparable quantity across sessions and skills,
		// whereas "819" means nothing without knowing it's logs. The item
		// counts are still the authoritative record (§34) and are shown in
		// full in the drill-down.
		double projectedXp = session.getProjectedXpTotal();
		String headline = projectedXp > 0
			? "+" + CurrentView.formatAbbreviated(projectedXp) + " xp"
			: String.format("%,d", session.getTotalNetRetained());
		titleLine.add(Ui.bold(headline, Palette.ACCENT), BorderLayout.EAST);
		text.add(titleLine);

		long activeSeconds = session.getActiveSeconds();
		int xp = session.getXpGained(session.getSkill());
		String when = session.getStartedAt() == null
			? ""
			: (isOpen ? DATE_TIME_FORMAT : DATE_FORMAT).format(session.getStartedAt());
		String rate = activeSeconds > 0
			? CurrentView.formatAbbreviated(Math.round(xp / (double) activeSeconds * 3600)) + "/hr"
			: "0/hr";
		text.add(Ui.dim(when + " · " + formatShortDuration(activeSeconds) + " · " + rate));

		header.add(text, BorderLayout.CENTER);
		container.add(Ui.fixHeight(header));

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!expanded.remove(session.getId()))
				{
					expanded.add(session.getId());
				}
				// rebuilt in place: the row's own container is replaced, not
				// the whole list, so scroll position is preserved
				rebuildRow(container, session);
			}
		});

		if (isOpen)
		{
			appendDetail(container, session);
		}

		return container;
	}

	private void rebuildRow(JPanel container, ActivitySession session)
	{
		JPanel replacement = buildRow(session);
		int index = -1;
		for (int i = 0; i < listPanel.getComponentCount(); i++)
		{
			if (listPanel.getComponent(i) == container)
			{
				index = i;
				break;
			}
		}
		if (index < 0)
		{
			return;
		}
		listPanel.remove(index);
		listPanel.add(replacement, index);
		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * SPEC.md §29's lifecycle ledger. Signed values make it read as
	 * arithmetic ending in Net retained - the only bold row and the only
	 * accent number. An item with a clean 1:1 flow (nothing dropped,
	 * consumed or repicked) collapses to a single line, so only the
	 * interesting item takes the space.
	 */
	private void appendDetail(JPanel container, ActivitySession session)
	{
		JPanel stats = new JPanel(new GridLayout(1, 2, 3, 0));
		stats.setOpaque(false);
		JLabel xpValue = Ui.bold(String.format("+%,d", session.getXpGained(session.getSkill())), Palette.TEXT);
		long activeSeconds = session.getActiveSeconds();
		JLabel rateValue = Ui.bold(activeSeconds > 0
			? String.format("%,d", Math.round(session.getXpGained(session.getSkill()) / (double) activeSeconds * 3600))
			: "0", Palette.TEXT);
		stats.add(Ui.tile("XP", xpValue, false));
		stats.add(Ui.tile("XP/HR", rateValue, false));
		container.add(Ui.gap(3));
		container.add(Ui.fixHeight(stats));

		container.add(Ui.gap(6));
		container.add(Ui.band("ITEM FLOW"));

		for (ItemFlowEntry entry : session.getItemFlow())
		{
			if (entry.getDirectlyAcquired() <= 0)
			{
				continue;
			}
			appendItemDetail(container, entry);
		}

		appendProjection(container, session);
		container.add(Ui.gap(8));
	}

	/**
	 * The projection <b>as it was recorded when this session ended</b>, read
	 * straight from the session and never recalculated (§33).
	 * <p>
	 * This is the point of freezing it. Change iron ore from iron bars to
	 * steel bars tomorrow and every past mining session would otherwise
	 * silently restate a different number - history would rewrite itself.
	 * Instead the new choice applies only to sessions recorded from then on,
	 * and each old session keeps both the figure and the product name it was
	 * actually resolved against.
	 */
	private void appendProjection(JPanel container, ActivitySession session)
	{
		List<ProjectedXp> projection = session.getProjection();
		if (projection.isEmpty())
		{
			return;
		}

		container.add(Ui.gap(6));
		container.add(Ui.band("PROJECTED AT THE TIME"));

		for (ProjectionBuilder.SkillTotal total : ProjectionBuilder.totals(projection))
		{
			container.add(Ui.ledgerRow(total.skill.getName(),
				"~ +" + CurrentView.formatAbbreviated(total.xp), Palette.DIM, false));
		}

		// naming the product each figure assumed is what makes an old record
		// still interpretable after the selection has moved on
		for (ProjectedXp p : projection)
		{
			String name = itemNames.getOrDefault(p.getItemId(), "Item #" + p.getItemId());
			JLabel line = Ui.label(String.format("%,d %s → %s", p.getQuantity(), name, p.getUseLabel()),
				FontManager.getRunescapeSmallFont(), Palette.DIMMEST);
			line.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
			container.add(Ui.fixHeight(line));
		}
	}

	private void appendItemDetail(JPanel container, ItemFlowEntry entry)
	{
		String name = itemNames.getOrDefault(entry.getItemId(), "Item #" + entry.getItemId());
		int generated = entry.getGenerated();
		int net = entry.getNetRetained();

		boolean simple = entry.getDropped() == 0 && entry.getRepicked() == 0 && entry.getConsumed() == 0;

		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setBackground(Palette.PANEL);
		head.setBorder(BorderFactory.createEmptyBorder(6, 2, 4, 2));

		JLabel icon = new JLabel();
		Dimension d = new Dimension(ITEM_ICON, ITEM_ICON);
		icon.setPreferredSize(d);
		icon.setMinimumSize(d);
		icon.setOpaque(true);
		icon.setBackground(Palette.WELL);
		icon.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
		AsyncBufferedImage image = itemManager.getImage(entry.getItemId());
		if (image != null)
		{
			image.addTo(icon);
		}
		head.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		text.add(Ui.bold(name, Palette.TEXT));
		text.add(Ui.dim(simple
			? String.format("%,d generated · %,d banked", generated, entry.getBanked())
			: String.format("%.1f%% retained", generated > 0 ? net * 100.0 / generated : 0)));
		head.add(text, BorderLayout.CENTER);
		container.add(Ui.fixHeight(head));

		if (simple)
		{
			// nothing was lost, so the ledger would just restate the header
			return;
		}

		Ui.Bar bar = new Ui.Bar(8);
		bar.set(generated > 0 ? net / (double) generated : 0, Palette.ACCENT, Palette.BORDER, Palette.BORDER);
		bar.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		container.add(bar);
		container.add(Ui.gap(6));

		container.add(Ui.ledgerRow("Generated", String.format("%,d", generated), Palette.TEXT, false));
		if (entry.getPickedUp() > 0)
		{
			container.add(Ui.ledgerRow("Picked up", String.format("+%,d", entry.getPickedUp()), Palette.TEXT, false));
		}
		if (entry.getDropped() > 0)
		{
			container.add(Ui.ledgerRow("Dropped", String.format("−%,d", entry.getDropped()), Palette.TEXT, false));
		}
		if (entry.getRepicked() > 0)
		{
			container.add(Ui.ledgerRow("Picked up again", String.format("+%,d", entry.getRepicked()), Palette.TEXT, false));
		}
		if (entry.getConsumed() > 0)
		{
			container.add(Ui.ledgerRow("Consumed", String.format("−%,d", entry.getConsumed()), Palette.TEXT, false));
		}
		container.add(Ui.ledgerRow("Banked", String.format("%,d", entry.getBanked()), Palette.TEXT, false));
		container.add(Ui.rule(Palette.BORDER));
		container.add(Ui.ledgerRow("Net retained", String.format("%,d", net), Palette.ACCENT, true));
	}

	private static String formatHours(long seconds)
	{
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
	}

	private static String formatShortDuration(long seconds)
	{
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		return hours > 0 ? String.format("%dh %02dm", hours, minutes) : minutes + "m";
	}
}
