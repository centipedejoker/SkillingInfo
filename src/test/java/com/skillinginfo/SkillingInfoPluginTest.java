package com.skillinginfo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Local dev/test launcher, not a JUnit test despite the name and location -
 * this is the standard RuneLite external-plugin pattern (matches
 * runelite/example-plugin). Run its main() to boot the full RuneLite client
 * with Skilling Info pre-loaded alongside every core plugin, for testing
 * against a real account. See README.md "Running locally".
 */
public class SkillingInfoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SkillingInfoPlugin.class);
		RuneLite.main(args);
	}
}
