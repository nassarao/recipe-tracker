package com.recipetracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

		int crafts = Math.max(1,
			(targetQuantity + recipe.getOutputQuantity() - 1) / recipe.getOutputQuantity());
		List<MaterialStatus> result = new ArrayList<>();
		for (MaterialRequirement material : recipe.getMaterials())
		{
			int required = (int) Math.ceil(material.getQuantityPerCraft() * crafts);
			result.add(new MaterialStatus(material.getName(), required,
				inventory.getOrDefault(material.getItemId(), 0)));
		}
		return Collections.unmodifiableList(result);
	}

	public static List<MaterialStatus> calculateAll(List<TrackedRecipe> trackedRecipes,
		Map<Integer, Integer> inventory)
	{
		Map<Integer, RequirementTotal> totals = new LinkedHashMap<>();
		for (TrackedRecipe tracked : trackedRecipes)
		{
			Recipe recipe = tracked.getRecipe();
			int crafts = Math.max(1, (tracked.getQuantity() + recipe.getOutputQuantity() - 1)
				/ recipe.getOutputQuantity());
			for (MaterialRequirement material : recipe.getMaterials())
			{
				add(totals, material, (int) Math.ceil(material.getQuantityPerCraft() * crafts), false);
			}
			for (MaterialRequirement tool : recipe.getTools())
			{
				add(totals, tool, 1, true);
			}
		}

		List<MaterialStatus> result = new ArrayList<>();
		for (RequirementTotal total : totals.values())
		{
			int owned = 0;
			for (int acceptedId : total.requirement.getAcceptedItemIds())
			{
				owned += inventory.getOrDefault(acceptedId, 0);
			}
			result.add(new MaterialStatus(total.requirement.getItemId(), total.requirement.getName(),
				total.required, owned, total.tool));
		}
		return Collections.unmodifiableList(result);
	}

	private static void add(Map<Integer, RequirementTotal> totals, MaterialRequirement requirement,
		int amount, boolean tool)
	{
		RequirementTotal total = totals.get(requirement.getItemId());
		if (total == null)
		{
			totals.put(requirement.getItemId(), new RequirementTotal(requirement, amount, tool));
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
