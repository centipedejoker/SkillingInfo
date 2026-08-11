package com.skillinginfo.ui;

import com.skillinginfo.session.ActivitySession;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Completed-session list (SPEC.md §43). Phase 1 shows the performance
 * summary only; item-flow lines (banked, future XP) are added once
 * ItemFlowTracker exists in Phase 2+.
 */
class HistoryView extends JPanel
{
	private final JPanel listPanel = new JPanel();

	HistoryView()
	{
		setLayout(new BorderLayout());
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
	}

	void refresh(List<ActivitySession> history)
	{
		listPanel.removeAll();

		if (history.isEmpty())
		{
			JLabel empty = new JLabel("No sessions recorded yet.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(new EmptyBorder(8, 8, 8, 8));
			listPanel.add(empty);
		}
		else
		{
			for (ActivitySession session : history)
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
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		JLabel title = new JLabel(session.getSkill() != null ? session.getSkill().getName() : "Unknown");
		title.setFont(FontManager.getRunescapeBoldFont());

		long activeSeconds = session.getActiveSeconds();
		int xp = session.getXpGained(session.getSkill());
		long rate = activeSeconds > 0 ? Math.round(xp / (double) activeSeconds * 3600) : 0;

		JLabel detail = new JLabel(String.format("%dm active  ·  %,d XP/hr  ·  +%,d XP",
			activeSeconds / 60, rate, xp));
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		row.add(title);
		row.add(detail);
		return row;
	}
}
