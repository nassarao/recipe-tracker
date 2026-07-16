package com.recipetracker;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.InventoryID;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Recipe Tracker",
	description = "Track crafting materials against the items in your inventory",
	tags = {"crafting", "materials", "inventory", "tracker"}
)
public class RecipeTrackerPlugin extends Plugin
{
	private static final String TRACKED_RECIPE_KEYS = "trackedRecipeKeys";
	private static final String LEGACY_TRACKED_RECIPES_KEY = "trackedRecipes";

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	@Inject private OverlayManager overlayManager;
	@Inject private RecipeTrackerOverlay overlay;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private RecipeTrackerConfig config;
	@Inject private RecipeRepository recipeRepository;
	@Inject private WikiRecipeService wikiRecipeService;
	@Inject private ScheduledExecutorService executor;

	private final Map<String, Integer> trackedQuantities = new LinkedHashMap<>();
	private volatile List<TrackedRecipe> trackedRecipes = Collections.emptyList();
	private volatile List<MaterialStatus> statuses = Collections.emptyList();
	private RecipeTrackerPanel panel;
	private NavigationButton navigationButton;
	private volatile PendingCapture pendingCapture;

	@Override
	protected void startUp()
	{
		recipeRepository.initialize();
		List<Recipe> recipes = recipeRepository.recipes();
		loadTrackedRecipes();
		panel = new RecipeTrackerPanel(this, recipes, itemManager);
		panel.updateTracking(trackedRecipes, statuses);

		navigationButton = NavigationButton.builder()
			.tooltip("Recipe Tracker")
			.icon(ImageUtil.loadImageResource(RecipeTrackerPlugin.class, "icon.png"))
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		overlayManager.add(overlay);
		requestInventoryRefresh();
		clientThread.invokeLater(() ->
		{
			recipeRepository.setItemIndex(ItemIndex.build(itemManager, client.getItemCount()));
			loadTrackedRecipes();
			RecipeTrackerPanel currentPanel = panel;
			if (currentPanel != null)
			{
				SwingUtilities.invokeLater(() -> currentPanel.setRecipes(recipeRepository.recipes()));
			}
			requestInventoryRefresh();
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
		}
		statuses = Collections.emptyList();
		trackedRecipes = Collections.emptyList();
		synchronized (trackedQuantities)
		{
			trackedQuantities.clear();
		}
		panel = null;
		navigationButton = null;
		pendingCapture = null;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INVENTORY.getId()
			|| event.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			refreshHeldItems();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refreshHeldItems();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		String option = event.getOption() == null ? "" : event.getOption().toLowerCase();
		boolean checkMaterials = option.contains("check") && option.contains("material");
		MenuEntry original = event.getMenuEntry();
		boolean ordinaryItem = "examine".equals(option)
			&& Math.max(event.getItemId(), original.getItemId()) > 0;
		if (!shouldShowTrackMenu() || (!checkMaterials && !ordinaryItem)
			|| menuAlreadyHasTrackEntry())
		{
			return;
		}

		int menuItemId = checkMaterials ? -1 : resolveMenuItemId(event, original);
		if (!checkMaterials && menuItemId <= 0)
		{
			return;
		}
		// Modern skill guides do not expose a trustworthy output item ID. Their
		// native response message provides the exact name after the player clicks.
		String outputName = checkMaterials ? "" : resolveMenuOutputName(event, original);
		if (!checkMaterials && outputName.isEmpty())
		{
			return;
		}
		Recipe cachedRecipe = recipeRepository.find(menuItemId, outputName);
		PendingCapture capture = new PendingCapture(outputName);
		MenuEntry trackEntry = client.getMenu().createMenuEntry(0)
			.setOption("Track materials")
			.setTarget(checkMaterials ? "" : outputName);

		if (checkMaterials)
		{
			// Reuse the native entry's action data. The player's click is processed as
			// the original Check materials action; no game action is invoked by plugin code.
			trackEntry
				.setType(original.getType())
				.setIdentifier(original.getIdentifier())
				.setParam0(original.getParam0())
				.setParam1(original.getParam1())
				.setItemId(original.getItemId())
				.onClick(menuEntry -> beginNativeCapture(capture));
		}
		else
		{
			trackEntry
				.setType(MenuAction.RUNELITE)
				.setItemId(menuItemId)
				.onClick(menuEntry ->
			{
				trackWikiRecipe(capture.outputName, cachedRecipe);
			});
		}
	}

	private boolean menuAlreadyHasTrackEntry()
	{
		for (MenuEntry entry : client.getMenuEntries())
		{
			if ("Track materials".equalsIgnoreCase(entry.getOption()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean shouldShowTrackMenu()
	{
		TrackMenuMode mode = config.trackMenuMode();
		return mode == TrackMenuMode.ALWAYS
			|| (mode == TrackMenuMode.SHIFT && client.isKeyPressed(KeyCode.KC_SHIFT));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		PendingCapture pending = pendingCapture;
		if (pending == null || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage()).trim();
		int separator = message.indexOf(':');
		if (separator <= 0 || !message.substring(separator + 1).toLowerCase().contains(" x "))
		{
			return;
		}
		String outputName = message.substring(0, separator).trim();
		if (!outputName.isEmpty() && pendingCapture == pending)
		{
			pendingCapture = null;
			trackWikiRecipe(outputName, recipeRepository.find(-1, outputName));
		}
	}

	private void beginNativeCapture(PendingCapture capture)
	{
		pendingCapture = capture;
		executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (pendingCapture == capture)
			{
				pendingCapture = null;
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Recipe Tracker: could not determine which item was selected.", null);
			}
		}), 3, TimeUnit.SECONDS);
	}

	private String resolveMenuOutputName(MenuEntryAdded event, MenuEntry entry)
	{
		String resolvedItemName = itemName(resolveMenuItemId(event, entry));
		if (!resolvedItemName.isEmpty()) return resolvedItemName;

		String target = Text.removeTags(event.getTarget() == null ? "" : event.getTarget()).trim();
		int targetItemId = recipeRepository.findItemId(target);
		return targetItemId > 0 ? itemName(targetItemId) : "";
	}

	private int resolveMenuItemId(MenuEntryAdded event, MenuEntry entry)
	{
		// Widget menus can retain a stale MenuEntry item ID. The widget and slot
		// parameters identify the item the player actually right-clicked.
		Widget widget = client.getWidget(entry.getParam1());
		if (widget != null)
		{
			Widget child = entry.getParam0() >= 0 ? widget.getChild(entry.getParam0()) : null;
			int itemId = child != null && child.getItemId() > 0 ? child.getItemId() : widget.getItemId();
			if (itemId > 0) return itemManager.canonicalize(itemId);
		}
		if (entry.getItemId() > 0) return itemManager.canonicalize(entry.getItemId());
		if (event.getItemId() > 0) return itemManager.canonicalize(event.getItemId());
		return -1;
	}

	private String itemName(int itemId)
	{
		if (itemId <= 0) return "";
		try
		{
			String name = itemManager.getItemComposition(itemId).getName();
			return name == null || "null".equalsIgnoreCase(name) ? "" : name.trim();
		}
		catch (RuntimeException ignored)
		{
			return "";
		}
	}

	private void onRepositoryChanged()
	{
		RecipeTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			SwingUtilities.invokeLater(() -> currentPanel.setRecipes(recipeRepository.recipes()));
		}
	}

	void trackRecipe(Recipe recipe)
	{
		synchronized (trackedQuantities)
		{
			trackedQuantities.putIfAbsent(recipe.getKey(), 1);
			rebuildTrackedRecipes();
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
		NavigationButton button = navigationButton;
		if (button != null)
		{
			SwingUtilities.invokeLater(() -> clientToolbar.openPanel(button));
		}
	}

	void searchWikiRecipes(String query, Consumer<List<String>> callback)
	{
		wikiRecipeService.search(query, executor).thenAccept(results ->
			SwingUtilities.invokeLater(() -> callback.accept(results)));
	}

	int findItemId(String name)
	{
		return recipeRepository.findItemId(name);
	}

	void trackWikiRecipe(String title, Recipe cachedRecipe)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"Recipe Tracker: loading the Wiki recipe for " + title + "...", null));
		wikiRecipeService.fetch(title, executor).thenAccept(result ->
		{
			if (!result.isPresent())
			{
				if (cachedRecipe != null)
				{
					clientThread.invokeLater(() ->
					{
						trackRecipe(cachedRecipe);
						client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
							"Recipe Tracker: the Wiki could not be refreshed; using the cached recipe.", null);
					});
				}
				else
				{
					clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Recipe Tracker: that Wiki page does not contain a supported recipe.", null));
				}
				return;
			}
			Recipe recipe = recipeRepository.addDefinition(result.get(), RecipeSource.WIKI_CACHE, true);
			onRepositoryChanged();
			clientThread.invokeLater(() -> trackRecipe(recipe));
		});
	}

	void untrackRecipe(Recipe recipe)
	{
		synchronized (trackedQuantities)
		{
			trackedQuantities.remove(recipe.getKey());
			rebuildTrackedRecipes();
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	void setTargetQuantity(Recipe recipe, int quantity)
	{
		synchronized (trackedQuantities)
		{
			if (!trackedQuantities.containsKey(recipe.getKey()))
			{
				return;
			}
			trackedQuantities.put(recipe.getKey(), Math.max(1, Math.min(10000, quantity)));
			rebuildTrackedRecipes();
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	List<TrackedRecipe> getTrackedRecipes()
	{
		return trackedRecipes;
	}

	List<MaterialStatus> getStatuses()
	{
		return statuses;
	}

	private void refreshHeldItems()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		addContainer(counts, client.getItemContainer(InventoryID.INVENTORY));
		addContainer(counts, client.getItemContainer(InventoryID.EQUIPMENT));
		addRunePouch(counts);
		applyEquippedStaffSubstitutions(counts);

		List<TrackedRecipe> trackedSnapshot = trackedRecipes;
		statuses = MaterialCalculator.calculateAll(trackedSnapshot, counts);
		RecipeTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			List<MaterialStatus> snapshot = statuses;
			SwingUtilities.invokeLater(() -> currentPanel.updateTracking(trackedSnapshot, snapshot));
		}
	}

	private void addContainer(Map<Integer, Integer> counts, ItemContainer container)
	{
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0)
				{
					int canonicalId = itemManager.canonicalize(item.getId());
					counts.merge(canonicalId, item.getQuantity(), Integer::sum);
				}
			}
		}
	}

	private void addRunePouch(Map<Integer, Integer> counts)
	{
		int[] runeVarbits = {Varbits.RUNE_POUCH_RUNE1, Varbits.RUNE_POUCH_RUNE2, Varbits.RUNE_POUCH_RUNE3,
			Varbits.RUNE_POUCH_RUNE4, Varbits.RUNE_POUCH_RUNE5, Varbits.RUNE_POUCH_RUNE6};
		int[] amountVarbits = {Varbits.RUNE_POUCH_AMOUNT1, Varbits.RUNE_POUCH_AMOUNT2, Varbits.RUNE_POUCH_AMOUNT3,
			Varbits.RUNE_POUCH_AMOUNT4, Varbits.RUNE_POUCH_AMOUNT5, Varbits.RUNE_POUCH_AMOUNT6};
		for (int i = 0; i < runeVarbits.length; i++)
		{
			int runeType = client.getVarbitValue(runeVarbits[i]);
			int amount = client.getVarbitValue(amountVarbits[i]);
			if (runeType > 0 && amount > 0)
			{
				int runeId = client.getEnum(982).getIntValue(runeType);
				if (runeId > 0)
				{
					counts.merge(itemManager.canonicalize(runeId), amount, Integer::sum);
				}
			}
		}
	}

	private void applyEquippedStaffSubstitutions(Map<Integer, Integer> counts)
	{
		int[] airStaves = {1381, 1397, 1405, 20730};
		int[] waterStaves = {1383, 1395, 1403, 20733};
		int[] earthStaves = {1385, 1399, 1407, 20736};
		int[] fireStaves = {1387, 1393, 1401, 20739};
		if (containsAny(counts, airStaves)) counts.put(556, Integer.MAX_VALUE / 4);
		if (containsAny(counts, waterStaves)) counts.put(555, Integer.MAX_VALUE / 4);
		if (containsAny(counts, earthStaves)) counts.put(557, Integer.MAX_VALUE / 4);
		if (containsAny(counts, fireStaves)) counts.put(554, Integer.MAX_VALUE / 4);
	}

	private static boolean containsAny(Map<Integer, Integer> counts, int[] ids)
	{
		for (int id : ids)
		{
			if (counts.getOrDefault(id, 0) > 0) return true;
		}
		return false;
	}

	private void requestInventoryRefresh()
	{
		clientThread.invokeLater(() ->
			refreshHeldItems());
	}

	private void loadTrackedRecipes()
	{
		String saved = configManager.getConfiguration(RecipeTrackerConfig.GROUP, TRACKED_RECIPE_KEYS);
		synchronized (trackedQuantities)
		{
			trackedQuantities.clear();
			if (saved != null && !saved.trim().isEmpty())
			{
				for (String entry : saved.split(";"))
				{
					int separator = entry.lastIndexOf('=');
					try
					{
						String key = entry.substring(0, separator);
						int quantity = Integer.parseInt(entry.substring(separator + 1));
						if (recipeRepository.findByKey(key) != null)
						{
							trackedQuantities.put(key, Math.max(1, Math.min(10000, quantity)));
						}
					}
					catch (NumberFormatException ignored)
					{
						// Ignore an invalid saved entry and continue loading the others.
					}
				}
			}
			else
			{
				migrateLegacyTrackedRecipes();
			}
			rebuildTrackedRecipes();
		}
	}

	private void rebuildTrackedRecipes()
	{
		List<TrackedRecipe> rebuilt = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : trackedQuantities.entrySet())
		{
			Recipe recipe = recipeRepository.findByKey(entry.getKey());
			if (recipe != null)
			{
				rebuilt.add(new TrackedRecipe(recipe, entry.getValue()));
			}
		}
		trackedRecipes = Collections.unmodifiableList(rebuilt);
	}

	private void saveTrackedRecipes()
	{
		StringBuilder saved = new StringBuilder();
		for (Map.Entry<String, Integer> entry : trackedQuantities.entrySet())
		{
			if (saved.length() > 0)
			{
				saved.append(';');
			}
			saved.append(entry.getKey()).append('=').append(entry.getValue());
		}
		configManager.setConfiguration(RecipeTrackerConfig.GROUP, TRACKED_RECIPE_KEYS, saved.toString());
	}

	private void migrateLegacyTrackedRecipes()
	{
		String legacy = configManager.getConfiguration(RecipeTrackerConfig.GROUP, LEGACY_TRACKED_RECIPES_KEY);
		if (legacy == null) return;
		for (String entry : legacy.split(","))
		{
			String[] parts = entry.split(":");
			try
			{
				int id = Integer.parseInt(parts[0]);
				int quantity = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
				for (Recipe recipe : recipeRepository.recipes())
				{
					if (recipe.getOutputItemId() == id)
					{
						trackedQuantities.put(recipe.getKey(), quantity);
						break;
					}
				}
			}
			catch (NumberFormatException ignored) { }
		}
		saveTrackedRecipes();
	}

	private static final class PendingCapture
	{
		private final String outputName;

		private PendingCapture(String outputName)
		{
			this.outputName = outputName;
		}
	}

	@Provides
	RecipeTrackerConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(RecipeTrackerConfig.class);
	}
}
