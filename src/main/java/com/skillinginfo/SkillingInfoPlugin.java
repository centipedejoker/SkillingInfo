package com.skillinginfo;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.ItemFlowEntry;
import com.skillinginfo.session.ItemUseStore;
import com.skillinginfo.session.SessionManager;
import com.skillinginfo.session.SessionRepository;
import com.skillinginfo.ui.SkillingInfoPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.TileItem;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Skilling Info",
	description = "Tracks personal skilling sessions, XP rates, idle time, activity output, actual pickups, dropped items and resources retained.",
	tags = {"skilling", "xp", "tracker", "slayer", "loot", "ironman"}
)
// §5/§37: Slayer task state comes from RuneLite's own Slayer plugin rather
// than being re-derived from chat messages, so it's declared as a
// dependency.
//
// §37 [v9]: so is the Loot Tracker. LootReceived is posted by that *plugin*
// and not by the client - disassembling every class in the client jar finds
// exactly one construction of it, in LootTrackerPlugin.addLoot. A comment
// here used to claim the opposite.
//
// Declaring both is necessary but not sufficient: PluginManager.startPlugin
// only force-stops conflicting plugins, it never force-starts dependencies.
// Enabling this plugin does not enable those, which is why their state is
// also surfaced to the panel.
@PluginDependency(SlayerPlugin.class)
@PluginDependency(LootTrackerPlugin.class)
public class SkillingInfoPlugin extends Plugin
{
	/**
	 * §18 `[v9]`: containers other than the bank that an item can move into
	 * while staying on the account, and that RuneLite actually surfaces as an
	 * {@code ItemContainerChanged}. Without these, stowing something reads as
	 * an unexplained inventory decrease.
	 * <p>
	 * Whether each fires promptly (rather than only when the player opens the
	 * container) is not guaranteed, and is why §50's unexplained-loss bucket
	 * remains the backstop rather than this list being the fix on its own.
	 * The coal bag, herb sack and gem sack have no container here at all.
	 */
	private static final Set<Integer> SIDE_CONTAINERS = ImmutableSet.of(
		InventoryID.LOOTING_BAG,
		InventoryID.SEED_BOX,
		InventoryID.SEED_VAULT,
		InventoryID.INV_GROUP_TEMP);

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

	@Inject
	private SlayerPluginService slayerPluginService;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	// §44 [v9]: history reads and writes run here, not on the client thread
	@Inject
	private ScheduledExecutorService executor;

	// §48 [v9]: the panel's buttons run on the EDT; session state is the
	// client thread's, so every mutation hops
	@Inject
	private ClientThread clientThread;

	// Resolved on the client thread (see onGameTick) and read from the EDT
	// by the panel - ItemManager.getItemComposition() asserts it's only
	// ever called on the client thread, so the UI must never call it
	// directly (caught live: SPEC.md v7 changelog).
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();

	// §18 [v9]: itemId → the id it is a note of, or -1. Memoised because
	// resolving it means an ItemComposition lookup, and this is consulted
	// from the per-tick item-flow correlation.
	private final Map<Integer, Integer> unnotedIds = new ConcurrentHashMap<>();

	// §5 [v9]: what the last LOGGED_IN was for. Compared rather than assumed
	// because that event also fires on every region change.
	private long lastAccountHash = -1;
	private EnumSet<WorldType> lastWorldType = EnumSet.noneOf(WorldType.class);

	private ItemUseStore itemUseStore;
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
		itemUseStore = new ItemUseStore(configManager);
		sessionRepository = new SessionRepository(executor);
		sessionManager = new SessionManager(config, sessionRepository, itemUseStore, this::unnotedId);
		// startUp runs on the EDT (PluginManager.startPlugin asserts it), so
		// the initial load goes to the executor like every other read
		reloadHistory(0);

		BufferedImage icon = buildIcon();
		panel = new SkillingInfoPanel(sessionManager, skillIconManager, itemUseStore, itemNames, itemManager, icon,
			clientThread::invoke);
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
		// A session in progress is real recorded data. Toggling the plugin
		// off used to discard it silently - onLogout already persists on the
		// way out, and shutdown has exactly the same obligation.
		if (sessionManager != null)
		{
			// §48a [v9]: shutDown runs on the EDT, and stop() both mutates
			// session state and writes the record out
			clientThread.invoke(sessionManager::stop);
		}

		clientToolbar.removeNavigation(navButton);

		// Deliberately not nulling sessionManager/panel: an event already in
		// flight would then NPE on the way through, turning a clean shutdown
		// into a stack trace in the user's log. The instance is discarded
		// either way.
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
		sessionManager.onSlayerTaskUpdate(
			slayerPluginService.getTask(),
			slayerPluginService.getTaskLocation(),
			slayerPluginService.getRemainingAmount());
		sessionManager.setLootTrackingVisible(isLootTrackerRunning());
		resolveItemNames();
		SwingUtilities.invokeLater(this::refreshPanel);
	}

	/**
	 * A rendering fault must not take the plugin down with it: without this,
	 * one exception on the EDT stops the panel updating at all, which is
	 * indistinguishable from tracking having stopped working.
	 */
	private void refreshPanel()
	{
		try
		{
			panel.refresh();
		}
		catch (Exception e)
		{
			log.warn("Skilling Info panel refresh failed", e);
		}
	}

	/**
	 * §44 `[v9]`: reads the account's history off the client thread, then
	 * hands it back on it.
	 * <p>
	 * Both halves matter. The read is disk I/O and a full JSON parse - ~44ms
	 * at 500 sessions, ~480ms at ten thousand - and it used to run on the
	 * client thread on <em>every region change</em>, because `LOGGED_IN`
	 * fires on those too. The hand-back has to be on the client thread
	 * because it also resolves item names through {@code ItemManager}.
	 */
	private void reloadHistory(long accountHash)
	{
		executor.execute(() -> {
			sessionRepository.useAccount(accountHash);
			List<ActivitySession> loaded = sessionRepository.loadAll();
			clientThread.invoke(() -> {
				sessionManager.setHistory(loaded);
				// resolved once here rather than every tick: the names of a
				// finished session cannot change, so re-walking all of
				// history each tick to hit a cache was pure waste (5.4ms per
				// tick at ten thousand sessions)
				loaded.forEach(this::resolveItemNames);
				SwingUtilities.invokeLater(this::refreshPanel);
			});
		});
	}

	/**
	 * Runs on the client thread (this handler is invoked there, not the
	 * EDT) so it's safe to call ItemManager here. Only the live session is
	 * walked per tick - history names are resolved once, when history is
	 * loaded.
	 */
	private void resolveItemNames()
	{
		ActivitySession current = sessionManager.getCurrentSession();
		if (current != null)
		{
			resolveItemNames(current);
		}
	}

	/**
	 * §18 `[v9]`: the id {@code itemId} is a note of, or -1 if it isn't a
	 * note. Lets the bank correlation pair a "withdraw as note" with the
	 * different id that actually lands in the inventory.
	 * <p>
	 * Deliberately only the note→item direction. RuneLite's own
	 * {@code ItemManager.canonicalize} reads {@code getLinkedNoteId()}
	 * exclusively behind a {@code getNote() != -1} guard, so that is the only
	 * direction the API is documented by usage to answer; asking an unnoted
	 * item for its noted form is unspecified.
	 * <p>
	 * Client thread only, via {@code getItemComposition}. Every caller
	 * reaches it through {@code onGameTick}, which is already on it.
	 */
	private int unnotedId(int itemId)
	{
		return unnotedIds.computeIfAbsent(itemId, id -> {
			ItemComposition composition = itemManager.getItemComposition(id);
			return composition.getNote() != -1 ? composition.getLinkedNoteId() : -1;
		});
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
		else if (event.getContainerId() == InventoryID.WORN)
		{
			sessionManager.onEquipmentChanged(event.getItemContainer().getItems());
		}
		else if (SIDE_CONTAINERS.contains(event.getContainerId()))
		{
			sessionManager.onSideContainerChanged(event.getContainerId(),
				event.getItemContainer().getItems());
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

	/**
	 * §37/§18: NPC drops, taken from RuneLite's own loot tracking rather
	 * than re-derived from raw events (§5). Only NPC and EVENT loot counts -
	 * a pickpocket or a clue casket is not this session's monster loot.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() != LootRecordType.NPC && event.getType() != LootRecordType.EVENT)
		{
			return;
		}

		Map<Integer, Integer> items = new HashMap<>();
		for (ItemStack stack : event.getItems())
		{
			items.merge(stack.getId(), stack.getQuantity(), Integer::sum);
		}
		sessionManager.onNpcLootReceived(items);
	}

	/**
	 * §18 `[v9]`: dying empties the inventory for reasons unrelated to the
	 * activity, and combat holds the consumption window permanently open.
	 */
	/**
	 * §37 `[v9]`: whether the Loot Tracker is switched on. Declaring it a
	 * dependency makes it injectable, not enabled - and with it off, combat
	 * loot silently never arrives.
	 */
	private boolean isLootTrackerRunning()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof LootTrackerPlugin)
			{
				return pluginManager.isPluginEnabled(plugin);
			}
		}
		return false;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			sessionManager.onLocalPlayerDeath();
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
		sessionManager.onGroundItemQuantityChanged(point, event.getItem(),
			event.getOldQuantity(), event.getNewQuantity());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		WorldPoint point = event.getTile().getWorldLocation();
		sessionManager.onGroundItemDespawned(point, event.getItem());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		sessionManager.onWorldViewUnloaded();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// LOGGED_IN fires between region changes too - every teleport,
			// dungeon, boat and world hop - so this is emphatically not a
			// "the player just logged in" hook. RuneLite's own XP Tracker
			// says as much in the same handler, and guards the same way.
			//
			// §5 [v9]: the guard has to be the account hash rather than the
			// login screen, because a reconnect can put the client on a
			// different account without ever showing one.
			long accountHash = client.getAccountHash();
			EnumSet<WorldType> worldType = client.getWorldType();
			if (accountHash == lastAccountHash && worldType.equals(lastWorldType))
			{
				return;
			}
			lastAccountHash = accountHash;
			lastWorldType = worldType;

			sessionManager.onAccountChanged();
			// account hash isn't resolvable until now - reload so history
			// comes from the right account's file, not the "unknown" bucket
			// used before login (SessionRepository)
			reloadHistory(accountHash);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			sessionManager.onLogout();
			SwingUtilities.invokeLater(this::refreshPanel);
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
