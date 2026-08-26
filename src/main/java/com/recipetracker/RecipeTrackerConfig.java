package com.recipetracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(RecipeTrackerConfig.GROUP)
public interface RecipeTrackerConfig extends Config
{
	String GROUP = "recipetracker";

	@ConfigItem(
		keyName = "trackMenuMode",
		name = "Right-click option",
		description = "Show Track materials on every item menu, only while Shift is held, or never",
		position = 0
	)
	default TrackMenuMode trackMenuMode()
	{
		return TrackMenuMode.ALWAYS;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show tracked recipes, reusable tools, and combined material progress over the game",
		position = 1
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "captureBuildClicks",
		name = "Track build-interface clicks",
		description = "Track recipes selected in Construction and Sailing build interfaces",
		position = 2
	)
	default boolean captureBuildClicks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "requireShiftForBuildClicks",
		name = "Require Shift for build clicks",
		description = "Only track Construction and Sailing interface selections while Shift is held",
		position = 3
	)
	default boolean requireShiftForBuildClicks()
	{
		return true;
	}
}
