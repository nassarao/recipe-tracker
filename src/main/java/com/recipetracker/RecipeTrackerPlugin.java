package com.recipetracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
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
	private static final String BANK_SNAPSHOT_KEY = "bankSnapshot";
	private static final long SAILING_CAPTURE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(8);
	private static final Type TRACKED_STATES_TYPE = new TypeToken<List<TrackedRecipeState>>() { }.getType();

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	@Inject private OverlayManager overlayManager;
	@Inject private RecipeTrackerOverlay overlay;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private Gson gson;
	@Inject private RecipeTrackerConfig config;
	@Inject private RecipeRepository recipeRepository;
	@Inject private WikiRecipeService wikiRecipeService;
	@Inject private ScheduledExecutorService executor;

	private final Object trackedLock = new Object();
	private volatile List<TrackedRecipe> trackedRecipes = Collections.emptyList();
	private volatile List<MaterialStatus> statuses = Collections.emptyList();
	private volatile Map<TrackedRecipe, List<MaterialStatus>> goalStatuses = Collections.emptyMap();
	private volatile Map<Integer, Integer> bankCounts = Collections.emptyMap();
	private volatile boolean bankSnapshotAvailable;
	private final Set<Widget> modifiedSkillGuideWidgets =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private String loadedBankProfileKey;
	private long sailingCaptureDeadline;
	private RecipeTrackerPanel panel;
	private NavigationButton navigationButton;

	@Override
	protected void startUp()
	{
		recipeRepository.initialize();
		loadBankSnapshot();
		List<Recipe> recipes = recipeRepository.recipes();
		loadTrackedRecipes();
		panel = new RecipeTrackerPanel(this, recipes, itemManager);
		panel.updateTracking(trackedRecipes, goalStatuses, statuses, bankSnapshotAvailable);

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
		goalStatuses = Collections.emptyMap();
		synchronized (trackedLock)
		{
			trackedRecipes = Collections.emptyList();
		}
		panel = null;
		navigationButton = null;
		bankCounts = Collections.emptyMap();
		bankSnapshotAvailable = false;
		clearLegacySkillGuideActions();
		loadedBankProfileKey = null;
		sailingCaptureDeadline = 0;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!shouldShowTrackMenu())
		{
			clearLegacySkillGuideActions();
			return;
		}

		// Legacy skill guides have no native row operation, but their icon
		// widgets contain the same canonical item IDs used by Wiki Lookup.
		addLegacySkillGuideActions(client.getWidget(InterfaceID.SkillGuide.ICONS));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK.getId())
		{
			updateBankSnapshot(event.getItemContainer());
			refreshHeldItems();
		}
		else if (event.getContainerId() == InventoryID.INVENTORY.getId()
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
			loadBankSnapshot();
			refreshHeldItems();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bankCounts = Collections.emptyMap();
			bankSnapshotAvailable = false;
			loadedBankProfileKey = null;
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		String option = event.getOption() == null ? "" : event.getOption().toLowerCase();
		boolean checkMaterials = option.contains("check") && option.contains("material");
		MenuEntry original = event.getMenuEntry();
		int interfaceId = original.getParam1() > 0
			? WidgetUtil.componentToInterface(original.getParam1()) : -1;
		// Plugin Hub rules prohibit modifying Construction menus. Sailing's
		// Shipwright action is also observed passively so no custom entry sends a
		// replacement action to the server.
		if (isConstructionInterface(interfaceId) || isSailingInterface(interfaceId))
		{
			return;
		}
		boolean ordinaryItem = "examine".equals(option)
			&& Math.max(event.getItemId(), original.getItemId()) > 0;
		if (!shouldShowTrackMenu() || (!checkMaterials && !ordinaryItem)
			|| menuAlreadyHasTrackEntry())
		{
			return;
		}

		int menuItemId = resolveMenuItemId(event, original);
		if (menuItemId <= 0)
		{
			return;
		}
		String outputName = resolveMenuOutputName(event, original);
		if (outputName.isEmpty())
		{
			return;
		}
		addTrackMenuEntry(menuItemId, outputName);
	}

	private void addTrackMenuEntry(int itemId, String outputName)
	{
		client.getMenu().createMenuEntry(0)
			.setOption("Track materials")
			.setTarget(outputName)
			.setType(MenuAction.RUNELITE)
			.setItemId(itemId)
			.onClick(menuEntry ->
			{
				trackResolvedItem(itemId, outputName);
			});
	}

	private void trackResolvedItem(int itemId, String outputName)
	{
		trackResolvedRecipeOrWiki(itemId, outputName);
	}

	private void trackResolvedRecipeOrWiki(int itemId, String outputName)
	{
		Recipe recipe = recipeRepository.find(itemId, outputName);
		if (recipe != null)
		{
			trackRecipe(recipe);
		}
		else
		{
			trackWikiRecipe(outputName, null);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Track materials".equalsIgnoreCase(event.getMenuOption()))
		{
			event.consume();
			Widget widget = resolveMenuWidget(event.getMenuEntry());
			int itemId = widget == null ? -1 : itemManager.canonicalize(widget.getItemId());
			String outputName = itemName(itemId);
			if (itemId > 0 && !outputName.isEmpty())
			{
				trackResolvedItem(itemId, outputName);
			}
			return;
		}
		captureBuildInterfaceClick(event);
	}

	private void captureBuildInterfaceClick(MenuOptionClicked event)
	{
		if (!shouldCaptureBuildClick())
		{
			return;
		}

		int component = event.getParam1();
		int interfaceId = component > 0 ? WidgetUtil.componentToInterface(component) : -1;
		if (!isConstructionInterface(interfaceId) && !isSailingInterface(interfaceId))
		{
			return;
		}

		if (isSailingInterface(interfaceId))
		{
			sailingCaptureDeadline = System.nanoTime() + SAILING_CAPTURE_WINDOW_NANOS;
		}

		int itemId = clickedItemId(event);
		String outputName = itemName(itemId);
		if (outputName.isEmpty())
		{
			outputName = Text.removeTags(event.getMenuTarget() == null ? "" : event.getMenuTarget()).trim();
		}
		if (outputName.isEmpty() || isNavigationTarget(outputName))
		{
			return;
		}

		trackResolvedRecipeOrWiki(itemId, outputName);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.captureBuildClicks() || event.getType() != ChatMessageType.GAMEMESSAGE
			|| sailingCaptureDeadline == 0 || System.nanoTime() - sailingCaptureDeadline > 0)
		{
			return;
		}

		Optional<RecipeDefinition> captured = NativeRecipeCapture.parseSailingMaterials(
			Text.removeTags(event.getMessage()));
		if (!captured.isPresent())
		{
			return;
		}
		sailingCaptureDeadline = 0;
		RecipeDefinition definition = captured.get();
		executor.execute(() ->
		{
			Recipe recipe = recipeRepository.addDefinition(definition, RecipeSource.NATIVE_CAPTURE, true);
			onRepositoryChanged();
			clientThread.invokeLater(() ->
			{
				trackRecipe(recipe);
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Recipe Tracker: captured the Shipwright recipe for " + recipe.getName() + ".", null);
			});
		});
	}

	private boolean shouldCaptureBuildClick()
	{
		return panel != null && config.captureBuildClicks()
			&& (!config.requireShiftForBuildClicks() || client.isKeyPressed(KeyCode.KC_SHIFT));
	}

	private int clickedItemId(MenuOptionClicked event)
	{
		int itemId = event.getItemId();
		if (itemId <= 0)
		{
			Widget widget = resolveMenuWidget(event.getMenuEntry());
			itemId = widget == null ? -1 : widget.getItemId();
		}
		return itemId > 0 ? itemManager.canonicalize(itemId) : -1;
	}

	static boolean isConstructionInterface(int interfaceId)
	{
		return interfaceId == InterfaceID.POH_FURNITURE_CREATION
			|| interfaceId == InterfaceID.POH_FURNITURE_CREATION_MENU;
	}

	static boolean isSailingInterface(int interfaceId)
	{
		return interfaceId == InterfaceID.SAILING_CUSTOMISATION
			|| interfaceId == InterfaceID.SAILING_BOAT_SELECTION
			|| interfaceId == InterfaceID.SAILING_BT_SELECTION
			|| interfaceId == InterfaceID.SAILING_MENU;
	}

	private static boolean isNavigationTarget(String target)
	{
		return "View".equalsIgnoreCase(target) || "Close".equalsIgnoreCase(target)
			|| "Back".equalsIgnoreCase(target) || "Select".equalsIgnoreCase(target)
			|| "Continue".equalsIgnoreCase(target) || "Check".equalsIgnoreCase(target)
			|| "Upgrade".equalsIgnoreCase(target);
	}

	private void addLegacySkillGuideActions(Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}

		if (widget.getItemId() > 0)
		{
			String[] actions = widget.getActions();
			String existing = actions != null && actions.length > 9 ? actions[9] : null;
			if (existing == null || existing.isEmpty() || "Track materials".equals(existing))
			{
				widget.setAction(9, "Track materials");
				modifiedSkillGuideWidgets.add(widget);
			}
		}

		Widget[] children = widget.getChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				addLegacySkillGuideActions(child);
			}
		}
	}

	private void clearLegacySkillGuideActions()
	{
		for (Widget widget : modifiedSkillGuideWidgets)
		{
			try
			{
				String[] actions = widget.getActions();
				if (actions != null && actions.length > 9
					&& "Track materials".equals(actions[9]))
				{
					widget.setAction(9, null);
				}
			}
			catch (RuntimeException ignored)
			{
				// The guide may have closed and invalidated its dynamic widgets.
			}
		}
		modifiedSkillGuideWidgets.clear();
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
		Widget widget = resolveMenuWidget(entry);
		if (widget != null)
		{
			int itemId = widget.getItemId();
			if (itemId > 0) return itemManager.canonicalize(itemId);
		}
		if (entry.getItemId() > 0) return itemManager.canonicalize(entry.getItemId());
		if (event.getItemId() > 0) return itemManager.canonicalize(event.getItemId());
		return -1;
	}

	private Widget resolveMenuWidget(MenuEntry entry)
	{
		Widget widget = client.getWidget(entry.getParam1());
		if (widget != null && entry.getParam0() >= 0)
		{
			Widget child = widget.getChild(entry.getParam0());
			if (child != null)
			{
				widget = child;
			}
		}
		return widget;
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
		synchronized (trackedLock)
		{
			if (findRoot(recipe.getKey()) == null)
			{
				List<TrackedRecipe> updated = new ArrayList<>(trackedRecipes);
				updated.add(new TrackedRecipe(recipe, 1));
				trackedRecipes = Collections.unmodifiableList(updated);
			}
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

	void removeTrackedGoal(TrackedRecipe tracked)
	{
		synchronized (trackedLock)
		{
			TrackedRecipe parent = tracked.getParent();
			if (parent == null)
			{
				List<TrackedRecipe> updated = new ArrayList<>(trackedRecipes);
				updated.remove(tracked);
				trackedRecipes = Collections.unmodifiableList(updated);
			}
			else
			{
				parent.removeChild(tracked);
			}
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	void setTargetQuantity(TrackedRecipe tracked, int quantity)
	{
		synchronized (trackedLock)
		{
			tracked.setQuantity(quantity);
			synchronizeChildQuantities(tracked);
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	void setCollapsed(TrackedRecipe tracked, boolean collapsed)
	{
		synchronized (trackedLock)
		{
			tracked.setCollapsed(collapsed);
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	Recipe findSubRecipe(TrackedRecipe parent, MaterialRequirement material)
	{
		Recipe candidate = recipeRepository.findCraftingRecipe(material);
		if (candidate == null)
		{
			return null;
		}
		for (TrackedRecipe ancestor = parent; ancestor != null; ancestor = ancestor.getParent())
		{
			Recipe ancestorRecipe = ancestor.getRecipe();
			if (ancestorRecipe.getKey().equals(candidate.getKey())
				|| (candidate.getOutputItemId() > 0
					&& candidate.getOutputItemId() == ancestorRecipe.getOutputItemId()))
			{
				return null;
			}
		}
		return candidate;
	}

	void toggleSubRecipe(TrackedRecipe parent, MaterialRequirement material)
	{
		synchronized (trackedLock)
		{
			TrackedRecipe existing = parent.childFor(material);
			if (existing != null)
			{
				parent.removeChild(existing);
			}
			else
			{
				Recipe recipe = findSubRecipe(parent, material);
				if (recipe == null)
				{
					return;
				}
				parent.addChild(new TrackedRecipe(recipe, requiredFor(parent, material)));
			}
			synchronizeChildQuantities(parent);
			saveTrackedRecipes();
		}
		requestInventoryRefresh();
	}

	private TrackedRecipe findRoot(String recipeKey)
	{
		for (TrackedRecipe tracked : trackedRecipes)
		{
			if (tracked.getRecipe().getKey().equals(recipeKey))
			{
				return tracked;
			}
		}
		return null;
	}

	private static int requiredFor(TrackedRecipe parent, MaterialRequirement material)
	{
		Recipe recipe = parent.getRecipe();
		int crafts = Math.max(1, (parent.getQuantity() + recipe.getOutputQuantity() - 1)
			/ recipe.getOutputQuantity());
		return Math.max(1, (int) Math.ceil(material.getQuantityPerCraft() * crafts));
	}

	private void synchronizeChildQuantities(TrackedRecipe parent)
	{
		List<TrackedRecipe> unmatched = new ArrayList<>(parent.getChildren());
		for (MaterialRequirement material : parent.getRecipe().getMaterials())
		{
			TrackedRecipe child = parent.childFor(material);
			if (child != null)
			{
				unmatched.remove(child);
				child.setQuantity(requiredFor(parent, material));
				synchronizeChildQuantities(child);
			}
		}
		for (TrackedRecipe orphan : unmatched)
		{
			parent.removeChild(orphan);
		}
	}

	List<TrackedRecipe> getTrackedRecipes()
	{
		return trackedRecipes;
	}

	List<MaterialStatus> getStatuses()
	{
		return statuses;
	}

	boolean hasBankSnapshot()
	{
		return bankSnapshotAvailable;
	}

	private void refreshHeldItems()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		addContainer(counts, client.getItemContainer(InventoryID.INVENTORY));
		addContainer(counts, client.getItemContainer(InventoryID.EQUIPMENT));
		addRunePouch(counts);
		applyEquippedStaffSubstitutions(counts);

		List<TrackedRecipe> trackedSnapshot = trackedRecipes;
		statuses = MaterialCalculator.calculateAll(trackedSnapshot, counts, bankCounts);
		Map<TrackedRecipe, List<MaterialStatus>> perGoal = new IdentityHashMap<>();
		for (TrackedRecipe tracked : trackedSnapshot)
		{
			collectGoalStatuses(tracked, counts, bankCounts, perGoal);
		}
		goalStatuses = Collections.unmodifiableMap(perGoal);
		RecipeTrackerPanel currentPanel = panel;
		if (currentPanel != null)
		{
			List<MaterialStatus> snapshot = statuses;
			Map<TrackedRecipe, List<MaterialStatus>> goalSnapshot = goalStatuses;
			boolean bankAvailable = bankSnapshotAvailable;
			SwingUtilities.invokeLater(() -> currentPanel.updateTracking(
				trackedSnapshot, goalSnapshot, snapshot, bankAvailable));
		}
	}

	private static void collectGoalStatuses(TrackedRecipe tracked, Map<Integer, Integer> inventory,
		Map<Integer, Integer> bank, Map<TrackedRecipe, List<MaterialStatus>> destination)
	{
		destination.put(tracked, MaterialCalculator.calculateDirect(tracked, inventory, bank));
		for (TrackedRecipe child : tracked.getChildren())
		{
			collectGoalStatuses(child, inventory, bank, destination);
		}
	}

	private void updateBankSnapshot(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		Map<Integer, Integer> snapshot = new HashMap<>();
		addContainer(snapshot, container);
		bankCounts = Collections.unmodifiableMap(snapshot);
		bankSnapshotAvailable = true;
		saveBankSnapshot();
	}

	private void loadBankSnapshot()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.equals(loadedBankProfileKey))
		{
			return;
		}

		loadedBankProfileKey = profileKey;
		String saved = configManager.getRSProfileConfiguration(RecipeTrackerConfig.GROUP, BANK_SNAPSHOT_KEY);
		Map<Integer, Integer> snapshot = new HashMap<>();
		if (saved != null && !saved.isEmpty())
		{
			for (String entry : saved.split(","))
			{
				int separator = entry.indexOf(':');
				if (separator <= 0)
				{
					continue;
				}
				try
				{
					int itemId = Integer.parseInt(entry.substring(0, separator));
					int quantity = Integer.parseInt(entry.substring(separator + 1));
					if (itemId > 0 && quantity > 0)
					{
						snapshot.put(itemId, quantity);
					}
				}
				catch (NumberFormatException ignored)
				{
					// Ignore one malformed cached item without discarding the rest of the bank.
				}
			}
		}
		bankCounts = Collections.unmodifiableMap(snapshot);
		bankSnapshotAvailable = saved != null;
	}

	private void saveBankSnapshot()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}

		StringBuilder saved = new StringBuilder();
		for (Map.Entry<Integer, Integer> entry : bankCounts.entrySet())
		{
			if (saved.length() > 0)
			{
				saved.append(',');
			}
			saved.append(entry.getKey()).append(':').append(entry.getValue());
		}
		configManager.setRSProfileConfiguration(RecipeTrackerConfig.GROUP, BANK_SNAPSHOT_KEY,
			saved.toString());
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
		synchronized (trackedLock)
		{
			List<TrackedRecipe> restored = new ArrayList<>();
			if (saved != null && !saved.trim().isEmpty())
			{
				if (saved.trim().startsWith("["))
				{
					try
					{
						List<TrackedRecipeState> states = gson.fromJson(saved, TRACKED_STATES_TYPE);
						if (states != null)
						{
							for (TrackedRecipeState state : states)
							{
								TrackedRecipe tracked = restoreTrackedRecipe(state);
								if (tracked != null)
								{
									restored.add(tracked);
								}
							}
						}
					}
					catch (RuntimeException ignored)
					{
						// A malformed tree should not prevent the plugin from starting.
					}
				}
				else
				{
					restoreFlatTrackedRecipes(saved, restored);
				}
			}
			else if (saved == null)
			{
				migrateLegacyTrackedRecipes(restored);
			}
			for (TrackedRecipe tracked : restored)
			{
				synchronizeChildQuantities(tracked);
			}
			trackedRecipes = Collections.unmodifiableList(restored);
			if (saved != null && !saved.trim().isEmpty() && !saved.trim().startsWith("["))
			{
				saveTrackedRecipes();
			}
			else if (saved == null && !restored.isEmpty())
			{
				saveTrackedRecipes();
			}
		}
	}

	private TrackedRecipe restoreTrackedRecipe(TrackedRecipeState state)
	{
		if (state == null || state.recipeKey == null)
		{
			return null;
		}
		Recipe recipe = recipeRepository.findByKey(state.recipeKey);
		if (recipe == null)
		{
			return null;
		}
		TrackedRecipe tracked = new TrackedRecipe(recipe, state.quantity);
		tracked.setCollapsed(state.collapsed);
		if (state.children != null)
		{
			for (TrackedRecipeState childState : state.children)
			{
				TrackedRecipe child = restoreTrackedRecipe(childState);
				if (child != null)
				{
					tracked.addChild(child);
				}
			}
		}
		return tracked;
	}

	private void saveTrackedRecipes()
	{
		List<TrackedRecipeState> states = new ArrayList<>();
		for (TrackedRecipe tracked : trackedRecipes)
		{
			states.add(toState(tracked));
		}
		configManager.setConfiguration(RecipeTrackerConfig.GROUP, TRACKED_RECIPE_KEYS,
			gson.toJson(states, TRACKED_STATES_TYPE));
	}

	private static TrackedRecipeState toState(TrackedRecipe tracked)
	{
		TrackedRecipeState state = new TrackedRecipeState();
		state.recipeKey = tracked.getRecipe().getKey();
		state.quantity = tracked.getQuantity();
		state.collapsed = tracked.isCollapsed();
		for (TrackedRecipe child : tracked.getChildren())
		{
			state.children.add(toState(child));
		}
		return state;
	}

	private void restoreFlatTrackedRecipes(String saved, List<TrackedRecipe> destination)
	{
		for (String entry : saved.split(";"))
		{
			int separator = entry.lastIndexOf('=');
			if (separator <= 0)
			{
				continue;
			}
			try
			{
				Recipe recipe = recipeRepository.findByKey(entry.substring(0, separator));
				int quantity = Integer.parseInt(entry.substring(separator + 1));
				if (recipe != null)
				{
					destination.add(new TrackedRecipe(recipe, quantity));
				}
			}
			catch (NumberFormatException ignored)
			{
				// Ignore one malformed legacy entry and continue loading the others.
			}
		}
	}

	private void migrateLegacyTrackedRecipes(List<TrackedRecipe> destination)
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
						destination.add(new TrackedRecipe(recipe, quantity));
						break;
					}
				}
			}
			catch (NumberFormatException ignored) { }
		}
	}

	@Provides
	RecipeTrackerConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(RecipeTrackerConfig.class);
	}
}
