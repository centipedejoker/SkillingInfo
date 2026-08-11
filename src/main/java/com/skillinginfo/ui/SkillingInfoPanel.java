package com.skillinginfo.ui;

import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.SessionManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Root sidebar panel (SPEC.md §65). Navigation is a row of small skill-icon
 * toggle buttons - one per skill that has history, plus a persistent
 * "current session" button - rather than a plain text tab switch. Clicking
 * a skill icon filters the panel to that skill's session history; clicking
 * the current-session button returns to the live idle/prompt/active view.
 * Matches the interaction Loot Tracker/XP Tracker use for per-entity
 * filtering, built on {@link SkillIconManager} (RuneLite core API).
 */
public class SkillingInfoPanel extends PluginPanel
{
	private static final String CURRENT_CARD = "CURRENT";
	private static final String HISTORY_CARD = "HISTORY";
	private static final int ICON_BUTTON_SIZE = 24;

	private final SessionManager sessionManager;
	private final SkillIconManager skillIconManager;
	private final ImageIcon currentIcon;

	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
	private final CurrentView currentView;
	private final HistoryView historyView;

	private final JButton currentButton;
	private Skill selectedSkill;
	private Set<Skill> renderedSkills = new LinkedHashSet<>();

	public SkillingInfoPanel(SessionManager sessionManager, SkillIconManager skillIconManager, ItemManager itemManager, BufferedImage pluginIcon)
	{
		super(false);
		this.sessionManager = sessionManager;
		this.skillIconManager = skillIconManager;
		this.currentIcon = new ImageIcon(pluginIcon);

		setLayout(new BorderLayout());

		currentView = new CurrentView(sessionManager, itemManager, this::refresh);
		historyView = new HistoryView();

		cards.add(currentView, CURRENT_CARD);
		cards.add(historyView, HISTORY_CARD);

		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);

		currentButton = buildIconButton(currentIcon, "Current session");
		currentButton.addActionListener(e -> selectSkill(null));
		tabBar.add(currentButton);

		add(tabBar, BorderLayout.NORTH);
		add(cards, BorderLayout.CENTER);

		selectSkill(null);
	}

	private JButton buildIconButton(ImageIcon icon, String tooltip)
	{
		JButton button = new JButton(icon);
		button.setToolTipText(tooltip);
		button.setPreferredSize(new Dimension(ICON_BUTTON_SIZE, ICON_BUTTON_SIZE));
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		button.setFocusPainted(false);
		button.setMargin(new java.awt.Insets(0, 0, 0, 0));
		return button;
	}

	private void selectSkill(Skill skill)
	{
		selectedSkill = skill;
		currentButton.setBorder(skill == null
			? BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2)
			: BorderFactory.createEmptyBorder(2, 2, 2, 2));
		cardLayout.show(cards, skill == null ? CURRENT_CARD : HISTORY_CARD);
		refresh();
	}

	private void rebuildTabBar()
	{
		Set<Skill> skills = new LinkedHashSet<>();
		for (ActivitySession session : sessionManager.getHistory())
		{
			skills.add(session.getSkill());
		}

		if (skills.equals(renderedSkills))
		{
			return;
		}
		renderedSkills = skills;

		// rebuild: keep the current-session button, replace the skill buttons
		tabBar.removeAll();
		tabBar.add(currentButton);
		for (Skill skill : skills)
		{
			ImageIcon icon = new ImageIcon(skillIconManager.getSkillImage(skill, true));
			JButton button = buildIconButton(icon, skill.getName());
			button.setBorder(skill == selectedSkill
				? BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2)
				: BorderFactory.createEmptyBorder(2, 2, 2, 2));
			button.addActionListener(e -> selectSkill(skill));
			tabBar.add(button);
		}
		tabBar.revalidate();
		tabBar.repaint();
	}

	public void refresh()
	{
		rebuildTabBar();
		currentView.refresh();
		if (selectedSkill != null)
		{
			historyView.refresh(sessionManager.getHistory(), selectedSkill);
		}
	}
}
