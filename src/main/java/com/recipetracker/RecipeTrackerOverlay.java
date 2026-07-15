package com.recipetracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class RecipeTrackerOverlay extends OverlayPanel
{
	private static final Color COMPLETE = new Color(64, 196, 99);
	private static final Color MISSING = new Color(255, 115, 105);

	private final RecipeTrackerPlugin plugin;
	private final RecipeTrackerConfig config;

	@Inject
	RecipeTrackerOverlay(RecipeTrackerPlugin plugin, RecipeTrackerConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		panelComponent.setPreferredSize(new Dimension(230, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<TrackedRecipe> trackedRecipes = plugin.getTrackedRecipes();
		List<MaterialStatus> statuses = plugin.getStatuses();
		if (!config.showOverlay() || trackedRecipes.isEmpty())
		{
			return null;
		}

		boolean allComplete = !statuses.isEmpty() && statuses.stream().allMatch(MaterialStatus::isComplete);
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Recipe Tracker")
			.color(allComplete ? COMPLETE : Color.WHITE)
			.build());

		for (TrackedRecipe tracked : trackedRecipes)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(tracked.getRecipe().getName())
				.right("x" + tracked.getQuantity())
				.build());
		}

		for (MaterialStatus status : statuses)
		{
			Color color = status.isComplete() ? COMPLETE : MISSING;
			panelComponent.getChildren().add(LineComponent.builder()
				.left(status.getName() + (status.isTool() ? " (tool)" : ""))
				.right(status.getInInventory() + " / " + status.getRequired())
				.leftColor(color)
				.rightColor(color)
				.build());
		}

		return super.render(graphics);
	}
}
