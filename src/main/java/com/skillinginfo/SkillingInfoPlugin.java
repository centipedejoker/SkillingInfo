package com.skillinginfo;

import com.google.inject.Provides;
import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.ItemFlowEntry;
import com.skillinginfo.session.SessionManager;
import com.skillinginfo.session.SessionRepository;
import com.skillinginfo.ui.SkillingInfoPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
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

	@Inject
	private ItemManager itemManager;

	// Resolved on the client thread (see onGameTick) and read from the EDT
	// by the panel - ItemManager.getItemComposition() asserts it's only
	// ever called on the client thread, so the UI must never call it
	// directly (caught live: SPEC.md v7 changelog).
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();

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
		panel = new SkillingInfoPanel(sessionManager, skillIconManager, config, itemNames, icon);
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
		resolveItemNames();
		SwingUtilities.invokeLater(() -> panel.refresh());
	}

	/**
	 * Runs on the client thread (this handler is invoked there, not the
	 * EDT) so it's safe to call ItemManager here. Resolves and caches the
	 * name of any item that has shown up in the current session's item
	 * flow, or any past session's (history), that hasn't been looked up
	 * yet - the history panel needs names too, not just the live view.
	 * computeIfAbsent makes repeat calls a cheap no-op once cached, so
	 * doing this every tick against a small history list is fine.
	 */
	private void resolveItemNames()
	{
		ActivitySession current = sessionManager.getCurrentSession();
		if (current != null)
		{
			resolveItemNames(current);
		}
		for (ActivitySession session : sessionManager.getHistory())
		{
			resolveItemNames(session);
		}
	}

	private void resolveItemNames(ActivitySession session)
	{
		for (ItemFlowEntry entry : session.getItemFlow())
		{
			itemNames.computeIfAbsent(entry.getItemId(), id -> itemManager.getItemComposition(id).getName());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			sessionManager.onInventoryChanged(event.getItemContainer().getItems());
		}
		else if (event.getContainerId() == InventoryID.BANK)
		{
			sessionManager.onBankChanged(event.getItemContainer().getItems());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Drop".equals(event.getMenuOption()))
		{
			sessionManager.onDropClicked(event.getItemId());
			return;
		}

		MenuAction action = event.getMenuAction();
		boolean isGroundItemAction = action == MenuAction.GROUND_ITEM_FIRST_OPTION
			|| action == MenuAction.GROUND_ITEM_SECOND_OPTION
			|| action == MenuAction.GROUND_ITEM_THIRD_OPTION
			|| action == MenuAction.GROUND_ITEM_FOURTH_OPTION
			|| action == MenuAction.GROUND_ITEM_FIFTH_OPTION;
		if (isGroundItemAction && "Take".equals(event.getMenuOption()))
		{
			sessionManager.onTakeClicked(event.getId());
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		WorldPoint point = event.getTile().getWorldLocation();
		sessionManager.onGroundItemSpawned(point, event.getItem());
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		WorldPoint point = event.getTile().getWorldLocation();
		sessionManager.onGroundItemQuantityChanged(point, event.getItem());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		WorldPoint point = event.getTile().getWorldLocation();
		sessionManager.onGroundItemDespawned(point, event.getItem());
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
