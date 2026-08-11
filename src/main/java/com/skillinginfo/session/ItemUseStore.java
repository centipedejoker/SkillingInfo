package com.skillinginfo.session;

import net.runelite.client.config.ConfigManager;

/**
 * Per-item future-XP choices, persisted one config key per item.
 * <p>
 * These deliberately aren't {@code @ConfigItem} entries: the set of
 * choosable items isn't known ahead of time and would make the config
 * screen enormous, so the selection lives inline in the panel next to the
 * item it applies to. This mirrors how banked-experience stores its
 * per-item activity picks (§5), via dynamic keys rather than a static
 * config interface.
 */
public class ItemUseStore
{
	private static final String CONFIG_GROUP = "skillinginfo";
	private static final String KEY_PREFIX = "itemUse_";

	private final ConfigManager configManager;

	public ItemUseStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * @return the player's chosen use for this item, falling back to the
	 * catalogue default. Returns null when the item has no known use at
	 * all, and {@link ItemUse#OFF} when they've explicitly disabled it.
	 */
	public ItemUse get(int itemId)
	{
		String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_PREFIX + itemId);
		if (stored != null)
		{
			ItemUse use = FutureXpResolver.findUse(itemId, stored);
			if (use != null)
			{
				return use;
			}
			// stored id no longer exists (catalogue changed between
			// versions) - fall through to the default rather than break
		}
		return FutureXpResolver.getDefaultUse(itemId);
	}

	public void set(int itemId, ItemUse use)
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_PREFIX + itemId, use.id);
	}
}
