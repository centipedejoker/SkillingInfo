package com.skillinginfo.ui;

import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.ItemFlowEntry;
import java.awt.BorderLayout;
import java.awt.Component;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Completed-session list for one skill at a time, reached via the skill
 * icon tab bar (SPEC.md §65, §43).
 * <p>
 * `[v7]` Backlog, not yet built: expandable rows to cut clutter, and a
 * per-skill summary total above the list (SPEC.md §65 backlog note) - for
 * now every item generated this session gets its own line, which is
 * correct but will get long for varied sessions.
 */
class HistoryView extends JPanel
{
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

	private final Map<Integer, String> itemNames;
	private final JPanel listPanel = new JPanel();

	HistoryView(Map<Integer, String> itemNames)
	{
		this.itemNames = itemNames;

		setLayout(new BorderLayout());
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
	}

	/**
	 * @param filterSkill only sessions matching this skill are shown - the
	 * tab bar always selects a specific skill before this view is visible.
	 */
	void refresh(List<ActivitySession> history, Skill filterSkill)
	{
		listPanel.removeAll();

		List<ActivitySession> filtered = history.stream()
			.filter(s -> s.getSkill() == filterSkill)
			.collect(java.util.stream.Collectors.toList());

		if (filtered.isEmpty())
		{
			JLabel empty = new JLabel("No " + filterSkill.getName() + " sessions recorded yet.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(new EmptyBorder(8, 8, 8, 8));
			listPanel.add(empty);
		}
		else
		{
			for (ActivitySession session : filtered)
			{
				listPanel.add(buildRow(session));
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private Component buildRow(ActivitySession session)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(4, 0, 4, 0),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));

		String when = session.getStartedAt() != null ? DATE_FORMAT.format(session.getStartedAt()) : session.getActivity();
		JLabel title = new JLabel(when);
		title.setFont(FontManager.getRunescapeSmallFont());

		long activeSeconds = session.getActiveSeconds();
		int xp = session.getXpGained(session.getSkill());
		long rate = activeSeconds > 0 ? Math.round(xp / (double) activeSeconds * 3600) : 0;

		JLabel detail = new JLabel(String.format("%dm active  ·  %,d XP/hr  ·  +%,d XP",
			activeSeconds / 60, rate, xp));
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		row.add(title);
		row.add(detail);

		for (ItemFlowEntry entry : session.getItemFlow())
		{
			if (entry.getGenerated() <= 0)
			{
				continue;
			}
			String name = itemNames.getOrDefault(entry.getItemId(), "Item #" + entry.getItemId());
			JLabel itemLine = new JLabel(String.format("%s: Gen %,d · Dropped %,d · Net %,d",
				name, entry.getGenerated(), entry.getDropped(), entry.getNetRetained()));
			itemLine.setFont(FontManager.getRunescapeSmallFont());
			itemLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			row.add(itemLine);
		}

		return row;
	}
}
