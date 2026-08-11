package com.skillinginfo;

import com.google.inject.Provides;
import com.skillinginfo.session.SessionManager;
import com.skillinginfo.session.SessionRepository;
import com.skillinginfo.ui.SkillingInfoPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Skilling Info",
	description = "Tracks personal skilling sessions, XP rates, idle time, activity output, actual pickups, dropped items and resources retained.",
	tags = {"skilling", "xp", "tracker", "slayer", "loot", "ironman"}
)
public class SkillingInfoPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SkillingInfoConfig config;

	@Inject
	private SkillIconManager skillIconManager;

	private SessionRepository sessionRepository;
	private SessionManager sessionManager;
	private SkillingInfoPanel panel;
	private NavigationButton navButton;

	@Provides
	SkillingInfoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SkillingInfoConfig.class);
	}

	@Override
	protected void startUp()
	{
		sessionRepository = new SessionRepository(client);
		sessionManager = new SessionManager(config, sessionRepository);
		sessionManager.init();

		BufferedImage icon = buildIcon();
		panel = new SkillingInfoPanel(sessionManager, skillIconManager, icon);
		panel.refresh();

		navButton = NavigationButton.builder()
			.tooltip("Skilling Info")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		sessionManager = null;
		panel = null;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		sessionManager.onStatChanged(event.getSkill(), event.getXp());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		sessionManager.onGameTick(client.getTickCount());
		SwingUtilities.invokeLater(() -> panel.refresh());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// account hash isn't resolvable until now - reload so history
			// comes from the right account's file, not the "unknown" bucket
			// used before login (SessionRepository)
			sessionManager.init();
			SwingUtilities.invokeLater(() -> panel.refresh());
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			sessionManager.onLogout();
			SwingUtilities.invokeLater(() -> panel.refresh());
		}
	}

	private BufferedImage buildIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(83, 155, 231));
		g.fillOval(1, 1, 14, 14);
		g.setColor(Color.WHITE);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
		g.drawString("SI", 3, 11);
		g.dispose();
		return image;
	}
}
