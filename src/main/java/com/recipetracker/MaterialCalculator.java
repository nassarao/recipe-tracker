package com.recipetracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MaterialCalculator
{
	private MaterialCalculator()
	{
	}

	public static List<MaterialStatus> calculate(Recipe recipe, int targetQuantity,
		Map<Integer, Integer> inventory)
	{
		if (recipe == null)
		{
			return Collections.emptyList();
		}

		return calculateDirect(new TrackedRecipe(recipe, targetQuantity), inventory,
			Collections.emptyMap());
	}

	static List<MaterialStatus> calculateDirect(TrackedRecipe tracked,
		Map<Integer, Integer> inventory, Map<Integer, Integer> bank)
	{
		Map<String, RequirementTotal> totals = new LinkedHashMap<>();
		int crafts = craftsFor(tracked.getRecipe(), tracked.getQuantity());
		for (MaterialRequirement material : tracked.getRecipe().getMaterials())
		{
			add(totals, material, required(material, crafts), false);
		}
		for (MaterialRequirement tool : tracked.getRecipe().getTools())
		{
			add(totals, tool, 1, true);
		}
		return statuses(totals, inventory, bank);
	}

	public static List<MaterialStatus> calculateAll(List<TrackedRecipe> trackedRecipes,
		Map<Integer, Integer> inventory)
	{
		return calculateAll(trackedRecipes, inventory, Collections.emptyMap());
	}

	public static List<MaterialStatus> calculateAll(List<TrackedRecipe> trackedRecipes,
		Map<Integer, Integer> inventory, Map<Integer, Integer> bank)
	{
		Map<String, RequirementTotal> totals = new LinkedHashMap<>();
		for (TrackedRecipe tracked : trackedRecipes)
		{
			Set<TrackedRecipe> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
			accumulate(tracked, tracked.getQuantity(), totals, ancestors);
		}
		return statuses(totals, inventory, bank);
	}

	private static void accumulate(TrackedRecipe tracked, int targetQuantity,
		Map<String, RequirementTotal> totals, Set<TrackedRecipe> ancestors)
	{
		if (!ancestors.add(tracked))
		{
			return;
		}
		Recipe recipe = tracked.getRecipe();
		int crafts = craftsFor(recipe, targetQuantity);
		for (MaterialRequirement material : recipe.getMaterials())
		{
			int amount = required(material, crafts);
			TrackedRecipe child = tracked.childFor(material);
			if (child == null)
			{
				add(totals, material, amount, false);
			}
			else
			{
				accumulate(child, amount, totals, ancestors);
			}
		}
		for (MaterialRequirement tool : recipe.getTools())
		{
			add(totals, tool, 1, true);
		}
		ancestors.remove(tracked);
	}

	private static List<MaterialStatus> statuses(Map<String, RequirementTotal> totals,
		Map<Integer, Integer> inventory, Map<Integer, Integer> bank)
	{
		List<MaterialStatus> result = new ArrayList<>();
		for (RequirementTotal total : totals.values())
		{
			int inInventory = 0;
			int inBank = 0;
			for (int acceptedId : total.requirement.getAcceptedItemIds())
			{
				inInventory += inventory.getOrDefault(acceptedId, 0);
				inBank += bank.getOrDefault(acceptedId, 0);
			}
			result.add(new MaterialStatus(total.requirement.getItemId(), total.requirement.getName(),
				total.required, inInventory, inBank, total.tool));
		}
		return Collections.unmodifiableList(result);
	}

	private static int craftsFor(Recipe recipe, int targetQuantity)
	{
		return Math.max(1, (targetQuantity + recipe.getOutputQuantity() - 1)
			/ recipe.getOutputQuantity());
	}

	private static int required(MaterialRequirement material, int crafts)
	{
		return (int) Math.ceil(material.getQuantityPerCraft() * crafts);
	}

	private static void add(Map<String, RequirementTotal> totals, MaterialRequirement requirement,
		int amount, boolean tool)
	{
		String key = requirement.getItemId() > 0 ? "id:" + requirement.getItemId()
			: "name:" + requirement.getName().toLowerCase(Locale.ENGLISH);
		RequirementTotal total = totals.get(key);
		if (total == null)
		{
			totals.put(key, new RequirementTotal(requirement, amount, tool));
		}
		else if (!tool)
		{
			total.required += amount;
		}
	}

	private static final class RequirementTotal
	{
		private final MaterialRequirement requirement;
		private int required;
		private final boolean tool;

		private RequirementTotal(MaterialRequirement requirement, int required, boolean tool)
		{
			this.requirement = requirement;
			this.required = required;
			this.tool = tool;
		}
	}
}
