package com.skillinginfo.ui;

import com.skillinginfo.session.ActivityClassifier;
import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.FutureXpResolver;
import com.skillinginfo.session.ItemFlowEntry;
import com.skillinginfo.session.ItemUse;
import com.skillinginfo.session.ItemUseStore;
import com.skillinginfo.session.PromptSummary;
import com.skillinginfo.session.SessionManager;
import com.skillinginfo.session.SessionState;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Renders the CURRENT tab's three states: idle (waiting for candidate
 * detection), prompted (Start/Ignore), and an active/paused session
 * (SPEC.md §6, §11). All text uses RuneLite's small UI font (§65) - a
 * ~225px sidebar has no room for default Swing/OS font sizes.
 */
class CurrentView extends JPanel
{
	private static final String IDLE_CARD = "IDLE";
	private static final String PROMPT_CARD = "PROMPT";
	private static final String SESSION_CARD = "SESSION";
	private static final int BUTTON_HEIGHT = 24;

	private final SessionManager sessionManager;
	private final ItemUseStore itemUseStore;
	private final Map<Integer, String> itemNames;
	private final Runnable onAction;

	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);

	private final JLabel promptTitle = new JLabel();
	private final JLabel promptBody = new JLabel();

	private final JLabel skillLabel = new JLabel();
	private final JLabel activityLabel = new JLabel();
	private final JLabel totalTimeValue = new JLabel();
	private final JLabel activeTimeValue = new JLabel();
	private final JLabel idleTimeValue = new JLabel();
	private final JLabel xpValue = new JLabel();
	private final JLabel activeRateValue = new JLabel();
	private final JLabel overallRateValue = new JLabel();
	private final JLabel producedLabel = new JLabel();
	private final JLabel producedValue = new JLabel();
	private final JLabel producedRateLabel = new JLabel();
	private final JLabel producedRateValue = new JLabel();
	private final JLabel retentionLabel = new JLabel();
	private final JLabel retentionValue = new JLabel();
	private final JButton pauseResumeButton = smallButton("Pause");
	private final JPanel itemFlowPanel = new JPanel();
	private final JPanel futureXpPanel = new JPanel();

	// Rows are cached and reused across refreshes. refresh() runs every
	// game tick, and tearing the panel down each time destroyed the use
	// selectors while the player was interacting with them - the dropdown
	// would close on open and appear to ignore clicks. Only rebuild when
	// the set of displayed items actually changes; otherwise just retext
	// the existing labels. (Same guard as SkillingInfoPanel's tab bar.)
	private final Map<Integer, JLabel> itemLines = new LinkedHashMap<>();
	private Set<Integer> renderedItemIds = new LinkedHashSet<>();

	CurrentView(SessionManager sessionManager, ItemUseStore itemUseStore, Map<Integer, String> itemNames, Runnable onAction)
	{
		this.sessionManager = sessionManager;
		this.itemUseStore = itemUseStore;
		this.itemNames = itemNames;
		this.onAction = onAction;

		setLayout(new BorderLayout());
		cards.add(buildIdleCard(), IDLE_CARD);
		cards.add(buildPromptCard(), PROMPT_CARD);
		cards.add(buildSessionCard(), SESSION_CARD);
		add(cards, BorderLayout.CENTER);
	}

	private static JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setPreferredSize(new Dimension(0, BUTTON_HEIGHT));
		button.setMargin(new java.awt.Insets(0, 2, 0, 2));
		return button;
	}

	private JPanel buildIdleCard()
	{
		JPanel panel = new JPanel(new BorderLayout());
		JLabel label = new JLabel("<html><center>No activity detected.<br/>Keep playing - Skilling Info will<br/>prompt you when it recognises<br/>a repeated activity.</center></html>", SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label, BorderLayout.CENTER);
		return panel;
	}

	private JPanel buildPromptCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		promptTitle.setFont(FontManager.getRunescapeBoldFont());
		promptTitle.setAlignmentX(CENTER_ALIGNMENT);
		promptBody.setFont(FontManager.getRunescapeSmallFont());
		promptBody.setAlignmentX(CENTER_ALIGNMENT);
		promptBody.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JButton startButton = smallButton("Start");
		JButton ignoreButton = smallButton("Ignore");
		startButton.addActionListener(e -> {
			sessionManager.start();
			onAction.run();
		});
		ignoreButton.addActionListener(e -> {
			sessionManager.ignore();
			onAction.run();
		});

		JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
		buttons.add(startButton);
		buttons.add(ignoreButton);
		buttons.setAlignmentX(CENTER_ALIGNMENT);

		panel.add(promptTitle);
		panel.add(Box.createVerticalStrut(8));
		panel.add(promptBody);
		panel.add(Box.createVerticalStrut(12));
		panel.add(buttons);
		return panel;
	}

	private JPanel buildSessionCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		skillLabel.setFont(FontManager.getRunescapeBoldFont());
		activityLabel.setFont(FontManager.getRunescapeSmallFont());
		activityLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel stats = new JPanel(new GridLayout(0, 2, 4, 2));
		stats.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		addStatRow(stats, "Total", totalTimeValue);
		addStatRow(stats, "Active", activeTimeValue);
		addStatRow(stats, "Idle", idleTimeValue);
		addStatRow(stats, "XP", xpValue);
		addStatRow(stats, "Active XP/hr", activeRateValue);
		addStatRow(stats, "Overall XP/hr", overallRateValue);
		// SPEC.md §40's per-activity outputs. Labels are set at refresh
		// time because the noun depends on the skill ("Logs" vs "Catches").
		addStatRow(stats, producedLabel, producedValue);
		addStatRow(stats, producedRateLabel, producedRateValue);
		addStatRow(stats, retentionLabel, retentionValue);

		itemFlowPanel.setLayout(new BoxLayout(itemFlowPanel, BoxLayout.Y_AXIS));
		itemFlowPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		futureXpPanel.setLayout(new BoxLayout(futureXpPanel, BoxLayout.Y_AXIS));
		futureXpPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		pauseResumeButton.addActionListener(e -> {
			if (sessionManager.getState() == SessionState.ACTIVE)
			{
				sessionManager.pause();
			}
			else
			{
				sessionManager.resume();
			}
			onAction.run();
		});

		JButton stopButton = smallButton("Stop Session");
		stopButton.addActionListener(e -> {
			sessionManager.stop();
			onAction.run();
		});

		JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
		buttons.add(pauseResumeButton);
		buttons.add(stopButton);

		panel.add(skillLabel);
		panel.add(activityLabel);
		panel.add(stats);
		panel.add(itemFlowPanel);
		panel.add(futureXpPanel);
		panel.add(Box.createVerticalGlue());
		panel.add(buttons);
		return panel;
	}

	private void addStatRow(JPanel stats, String label, JLabel value)
	{
		addStatRow(stats, new JLabel(label), value);
	}

	private void addStatRow(JPanel stats, JLabel labelComponent, JLabel value)
	{
		labelComponent.setFont(FontManager.getRunescapeSmallFont());
		labelComponent.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setHorizontalAlignment(SwingConstants.RIGHT);
		stats.add(labelComponent);
		stats.add(value);
	}

	void refresh()
	{
		SessionState state = sessionManager.getState();
		switch (state)
		{
			case PROMPTED:
			{
				PromptSummary prompt = sessionManager.getPendingPrompt();
				if (prompt != null)
				{
					promptTitle.setText(prompt.getSkill().getName() + " activity detected");
					promptBody.setText("<html><center>" + prompt.getDropCount() + " XP drops<br/>"
						+ prompt.getElapsedSeconds() + " seconds<br/>+" + prompt.getTotalXp() + " XP</center></html>");
				}
				cardLayout.show(cards, PROMPT_CARD);
				break;
			}
			case ACTIVE:
			case PAUSED:
			{
				ActivitySession session = sessionManager.getCurrentSession();
				if (session == null)
				{
					cardLayout.show(cards, IDLE_CARD);
					break;
				}
				skillLabel.setText(session.getSkill().getName());
				activityLabel.setText(session.getActivity());

				long total = sessionManager.getClock().getTotalSeconds();
				long active = sessionManager.getClock().getActiveSeconds();
				long idle = sessionManager.getClock().getIdleSeconds();
				int xp = session.getXpGained(session.getSkill());

				totalTimeValue.setText(formatDuration(total));
				activeTimeValue.setText(formatDuration(active));
				idleTimeValue.setText(formatDuration(idle));
				xpValue.setText("+" + xp);
				activeRateValue.setText(formatRate(xp, active));
				overallRateValue.setText(formatRate(xp, total));
				pauseResumeButton.setText(state == SessionState.ACTIVE ? "Pause" : "Resume");

				refreshActivityOutputs(session, active);

				refreshItemFlow(session.getItemFlow());
				refreshFutureXp(session);

				cardLayout.show(cards, SESSION_CARD);
				break;
			}
			default:
				cardLayout.show(cards, IDLE_CARD);
				break;
		}
	}

	/**
	 * SPEC.md §11/§29 OUTPUT section - one compact line per item acquired
	 * this session, from either channel (direct skilling output or ground
	 * pickup - §20a). Gated on directlyAcquired, not generated, so a
	 * pickup-only item (no matching same-tick XP) still shows up.
	 * <p>
	 * `[v7]` Net retained only, not the full generated/dropped breakdown -
	 * nobody needs "what I dropped" at a glance, only what they ended up
	 * with; the full breakdown is backlog for the expandable detail view
	 * (SPEC.md §65). This also keeps each item to one line, since the
	 * two-line/multi-stat version was overflowing the sidebar's width.
	 */
	private void refreshItemFlow(Collection<ItemFlowEntry> entries)
	{
		List<ItemFlowEntry> visible = new java.util.ArrayList<>();
		Set<Integer> visibleIds = new LinkedHashSet<>();
		for (ItemFlowEntry entry : entries)
		{
			if (entry.getDirectlyAcquired() > 0)
			{
				visible.add(entry);
				visibleIds.add(entry.getItemId());
			}
		}

		if (!visibleIds.equals(renderedItemIds))
		{
			rebuildItemFlow(visible, visibleIds);
		}

		// cheap path: the rows already exist, just update their numbers
		for (ItemFlowEntry entry : visible)
		{
			JLabel line = itemLines.get(entry.getItemId());
			if (line != null)
			{
				String name = itemNames.getOrDefault(entry.getItemId(), "Item #" + entry.getItemId());
				String text = ItemFlowFormat.line(name, entry);
				if (!text.equals(line.getText()))
				{
					line.setText(text);
				}
			}
		}
	}

	private void rebuildItemFlow(List<ItemFlowEntry> visible, Set<Integer> visibleIds)
	{
		renderedItemIds = visibleIds;
		itemLines.clear();
		itemFlowPanel.removeAll();

		for (ItemFlowEntry entry : visible)
		{
			String name = itemNames.getOrDefault(entry.getItemId(), "Item #" + entry.getItemId());

			JLabel line = new JLabel(ItemFlowFormat.line(name, entry));
			line.setFont(FontManager.getRunescapeSmallFont());
			itemLines.put(entry.getItemId(), line);
			itemFlowPanel.add(line);

			// only items with a genuine decision get a selector - a
			// dropdown on every row would be the clutter §65 warns about
			if (FutureXpResolver.hasChoice(entry.getItemId()))
			{
				itemFlowPanel.add(buildUseSelector(entry.getItemId()));
			}
		}

		itemFlowPanel.revalidate();
		itemFlowPanel.repaint();
	}

	/**
	 * Inline per-item future-XP selector (SPEC.md §33/§35). Lives next to
	 * the item rather than in the RuneLite config screen: the set of
	 * choosable items isn't known ahead of time, and the choice only makes
	 * sense in the context of the item it applies to - the same reasoning
	 * banked-experience uses for its per-item activity picker (§5).
	 */
	private JComboBox<ItemUse> buildUseSelector(int itemId)
	{
		List<ItemUse> options = FutureXpResolver.getSelectableUses(itemId);
		JComboBox<ItemUse> combo = new JComboBox<>(new DefaultComboBoxModel<>(options.toArray(new ItemUse[0])));
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setSelectedItem(itemUseStore.get(itemId));
		combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_HEIGHT));
		combo.setRenderer((list, value, index, selected, focused) -> {
			JLabel label = new JLabel(value == null ? "" : value.label);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setOpaque(true);
			label.setBackground(selected ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			label.setForeground(ColorScheme.TEXT_COLOR);
			return label;
		});
		combo.addActionListener(e -> {
			ItemUse chosen = (ItemUse) combo.getSelectedItem();
			if (chosen != null)
			{
				itemUseStore.set(itemId, chosen);
				onAction.run();
			}
		});
		return combo;
	}

	/**
	 * SPEC.md §40's per-activity outputs: units produced, units/hour, and
	 * retention (§32). Rows hide themselves when there's nothing to show,
	 * so a session that hasn't produced anything - or a skill with no
	 * item output at all - doesn't display empty stats.
	 */
	private void refreshActivityOutputs(ActivitySession session, long activeSeconds)
	{
		int produced = session.getTotalGenerated();
		boolean show = produced > 0;

		producedLabel.setVisible(show);
		producedValue.setVisible(show);
		producedRateLabel.setVisible(show);
		producedRateValue.setVisible(show);

		if (show)
		{
			String noun = ActivityClassifier.outputNoun(session.getSkill());
			producedLabel.setText(noun);
			producedValue.setText(String.format("%,d", produced));

			producedRateLabel.setText(noun + "/hr");
			producedRateValue.setText(activeSeconds > 0
				? String.format("%,d", Math.round(produced / (double) activeSeconds * 3600))
				: "0");
		}

		double retention = session.getRetentionRate();
		boolean showRetention = retention >= 0;
		retentionLabel.setVisible(showRetention);
		retentionValue.setVisible(showRetention);
		if (showRetention)
		{
			retentionLabel.setText("Retention");
			retentionValue.setText(String.format("%.1f%%", retention * 100));
		}
	}

	/**
	 * SPEC.md §11's FUTURE XP section - per-skill totals for what the
	 * session's retained/banked resources would eventually yield, each
	 * labelled with its confidence tier (§33) so a projection can never
	 * be mistaken for earned XP. Renders nothing at all when no item
	 * resolves, which is the common and correct case (§35).
	 */
	private void refreshFutureXp(ActivitySession session)
	{
		futureXpPanel.removeAll();

		List<FutureXpSummary.Row> rows = FutureXpSummary.build(session, itemUseStore);
		if (!rows.isEmpty())
		{
			JLabel header = new JLabel("Future XP");
			header.setFont(FontManager.getRunescapeSmallFont());
			header.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			futureXpPanel.add(header);

			for (FutureXpSummary.Row row : rows)
			{
				JLabel line = new JLabel(row.format());
				line.setFont(FontManager.getRunescapeSmallFont());
				futureXpPanel.add(line);
			}
		}

		futureXpPanel.revalidate();
		futureXpPanel.repaint();
	}

	private static String formatDuration(long totalSeconds)
	{
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

	private static String formatRate(int xp, long seconds)
	{
		if (seconds <= 0)
		{
			return "0";
		}
		long perHour = Math.round(xp / (double) seconds * 3600);
		return String.format("%,d", perHour);
	}
}
