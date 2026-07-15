package com.recipetracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

@Singleton
public class RecipeRepository
{
	private static final int SCHEMA_VERSION = 1;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final File cacheFile = new File(new File(RuneLite.RUNELITE_DIR, "recipe-tracker"), "recipe-cache.json");
	private final Map<String, Recipe> recipesByKey = new LinkedHashMap<>();
	private final Map<String, RecipeDefinition> cachedDefinitions = new LinkedHashMap<>();
	private volatile List<Recipe> snapshot = Collections.emptyList();
	private volatile ItemIndex itemIndex;

	public synchronized void initialize()
	{
		recipesByKey.clear();
		cachedDefinitions.clear();
		loadCache();
		publish();
	}

	public synchronized void setItemIndex(ItemIndex index)
	{
		itemIndex = index;
		List<Recipe> current = new ArrayList<>(recipesByKey.values());
		recipesByKey.clear();
		for (Recipe recipe : current)
		{
			merge(resolve(recipe));
		}
		publish();
	}

	public List<Recipe> recipes()
	{
		return snapshot;
	}

	public Recipe findByKey(String key)
	{
		synchronized (this)
		{
			return recipesByKey.get(key);
		}
	}

	public Recipe find(int itemId, String target)
	{
		return RecipeResolver.find(snapshot, itemId, target);
	}

	public synchronized Recipe addDefinition(RecipeDefinition definition, RecipeSource source, boolean persist)
	{
		definition.source = source.name();
		Recipe recipe = toRecipe(definition, source);
		merge(recipe);
		if (persist)
		{
			cachedDefinitions.put(recipe.getKey(), definition);
			writeCache();
		}
		publish();
		return recipesByKey.get(recipe.getKey());
	}

	public int findItemId(String name)
	{
		ItemIndex index = itemIndex;
		return index == null ? -1 : index.findId(name);
	}

	public String findItemName(int id)
	{
		ItemIndex index = itemIndex;
		return index == null ? null : index.findName(id);
	}

	private void loadCache()
	{
		if (!cacheFile.isFile())
		{
			return;
		}
		try (Reader reader = new FileReader(cacheFile))
		{
			mergeFile(gson.fromJson(reader, RecipeFile.class), RecipeSource.WIKI_CACHE, true);
		}
		catch (IOException | RuntimeException ignored)
		{
			// A stale cache must not prevent startup.
		}
	}

	private void mergeFile(RecipeFile file, RecipeSource source, boolean rememberForCache)
	{
		if (file == null || file.schemaVersion != SCHEMA_VERSION || file.recipes == null)
		{
			return;
		}
		for (RecipeDefinition definition : file.recipes)
		{
			try
			{
				if (rememberForCache && definition.source != null
					&& !RecipeSource.WIKI_CACHE.name().equals(definition.source))
				{
					continue;
				}
				RecipeSource effectiveSource = source;
				if (definition.source != null)
				{
					try
					{
						effectiveSource = RecipeSource.valueOf(definition.source);
					}
					catch (IllegalArgumentException ignored) { }
				}
				Recipe recipe = toRecipe(definition, effectiveSource);
				merge(recipe);
				if (rememberForCache)
				{
					cachedDefinitions.put(recipe.getKey(), definition);
				}
			}
			catch (RuntimeException ignored)
			{
				// Invalid rows are isolated instead of discarding the complete file.
			}
		}
	}

	private Recipe toRecipe(RecipeDefinition definition, RecipeSource source)
	{
		String key = definition.key == null || definition.key.trim().isEmpty()
			? RecipeKey.of(definition.name, definition.category, definition.variant) : definition.key;
		List<MaterialRequirement> materials = new ArrayList<>();
		List<MaterialRequirement> tools = new ArrayList<>();
		for (RecipeDefinition.RequirementDefinition requirement : definition.requirements)
		{
			int primaryId = resolveId(requirement.itemId, requirement.name);
			List<Integer> alternatives = new ArrayList<>(requirement.alternativeItemIds);
			for (String alternative : requirement.alternatives)
			{
				int id = resolveId(0, alternative);
				if (id > 0)
				{
					alternatives.add(id);
				}
			}
			int[] ids = alternatives.stream().mapToInt(Integer::intValue).toArray();
			MaterialRequirement converted = new MaterialRequirement(primaryId, requirement.name,
				requirement.quantity, ids);
			("tool".equalsIgnoreCase(requirement.type) ? tools : materials).add(converted);
		}
		int outputId = resolveId(definition.outputItemId, definition.name);
		return new Recipe(key, source, outputId, definition.name, definition.category,
			Math.max(1, definition.outputQuantity), Collections.unmodifiableList(materials),
			Collections.unmodifiableList(tools));
	}

	private Recipe resolve(Recipe recipe)
	{
		int outputId = resolveId(recipe.getOutputItemId(), recipe.getName());
		return recipe.withResolvedRequirements(outputId, resolve(recipe.getMaterials()), resolve(recipe.getTools()));
	}

	private List<MaterialRequirement> resolve(Collection<MaterialRequirement> requirements)
	{
		List<MaterialRequirement> result = new ArrayList<>();
		for (MaterialRequirement requirement : requirements)
		{
			int[] accepted = requirement.getAcceptedItemIds();
			List<Integer> alternatives = new ArrayList<>();
			for (int acceptedId : accepted)
			{
				if (acceptedId > 0 && acceptedId != requirement.getItemId()) alternatives.add(acceptedId);
			}
			result.add(new MaterialRequirement(resolveId(requirement.getItemId(), requirement.getName()),
				requirement.getName(), requirement.getQuantityPerCraft(),
				alternatives.stream().mapToInt(Integer::intValue).toArray()));
		}
		return result;
	}

	private int resolveId(int existingId, String name)
	{
		if (existingId > 0)
		{
			return existingId;
		}
		ItemIndex index = itemIndex;
		return index == null ? -1 : index.findId(name);
	}

	private void merge(Recipe recipe)
	{
		Recipe current = recipesByKey.get(recipe.getKey());
		if (current == null || recipe.getSource().getPriority() >= current.getSource().getPriority())
		{
			recipesByKey.put(recipe.getKey(), recipe);
		}
	}

	private void publish()
	{
		List<Recipe> recipes = new ArrayList<>(recipesByKey.values());
		recipes.sort(Comparator.comparing(Recipe::getCategory).thenComparing(Recipe::getName));
		snapshot = Collections.unmodifiableList(recipes);
	}

	private void writeCache()
	{
		File directory = cacheFile.getParentFile();
		if (!directory.isDirectory() && !directory.mkdirs())
		{
			return;
		}
		RecipeFile file = new RecipeFile();
		file.recipes.addAll(cachedDefinitions.values());
		try (FileWriter writer = new FileWriter(cacheFile))
		{
			gson.toJson(file, writer);
		}
		catch (IOException ignored)
		{
			// The in-memory recipe remains usable if persistence fails.
		}
	}
}
