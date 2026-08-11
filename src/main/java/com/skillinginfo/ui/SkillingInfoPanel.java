package com.skillinginfo.ui;

import com.skillinginfo.session.SessionManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Root sidebar panel: a Current/History tab switch over the two views
 * (SPEC.md §41). Plain Swing buttons are used for the tab switch rather
 * than RuneLite's MaterialTabGroup to keep this scaffold's wiring
 * unambiguous; swapping in MaterialTabGroup later for a closer look-and-feel
 * match is a drop-in change.
 */
public class SkillingInfoPanel extends PluginPanel
{
	private static final String CURRENT_CARD = "CURRENT";
	private static final String HISTORY_CARD = "HISTORY";

	private final SessionManager sessionManager;
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final CurrentView currentView;
	private final HistoryView historyView;

	private final JButton currentTab = new JButton("Current");
	private final JButton historyTab = new JButton("History");

	public SkillingInfoPanel(SessionManager sessionManager)
	{
		super(false);
		this.sessionManager = sessionManager;

		setLayout(new BorderLayout());

		currentView = new CurrentView(sessionManager, this::refresh);
		historyView = new HistoryView();

		cards.add(currentView, CURRENT_CARD);
		cards.add(historyView, HISTORY_CARD);

		JPanel tabs = new JPanel(new BorderLayout());
		tabs.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
		currentTab.addActionListener(e -> cardLayout.show(cards, CURRENT_CARD));
		historyTab.addActionListener(e -> cardLayout.show(cards, HISTORY_CARD));
		for (JButton button : new JButton[]{currentTab, historyTab})
		{
			button.setFocusPainted(false);
			button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			button.setForeground(ColorScheme.TEXT_COLOR);
			button.setPreferredSize(new Dimension(0, 28));
		}
		tabs.add(currentTab, BorderLayout.WEST);
		tabs.add(historyTab, BorderLayout.EAST);

		add(tabs, BorderLayout.NORTH);
		add(cards, BorderLayout.CENTER);

		cardLayout.show(cards, CURRENT_CARD);
	}

	public void refresh()
	{
		currentView.refresh();
		historyView.refresh(sessionManager.getHistory());
	}
}
