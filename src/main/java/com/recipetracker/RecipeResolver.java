package com.recipetracker;

import java.util.List;
import java.util.Locale;

final class RecipeResolver
{
	private RecipeResolver()
	{
	}

	static Recipe find(List<Recipe> recipes, int itemId, String menuTarget)
	{
		for (Recipe recipe : recipes)
		{
			if (recipe.getOutputItemId() == itemId)
			{
				return recipe;
			}
		}

		String targetName = normalize(menuTarget);
		Recipe match = null;
		for (Recipe recipe : recipes)
		{
			String recipeName = normalize(recipe.getName());
			if (recipeName.equals(targetName)
				|| (!recipeName.isEmpty() && targetName.contains(recipeName)))
			{
				if (match != null)
				{
					return null;
				}
				match = recipe;
			}
		}
		return match;
	}

	private static String normalize(String value)
	{
		if (value == null)
		{
			return "";
		}

		return value
			.replaceAll("<[^>]*>", "")
			.replace('\u00a0', ' ')
			.toLowerCase(Locale.ENGLISH)
			.replace(" (flatpack)", "")
			.replace(" (boat facility)", "")
			.trim()
			.replaceAll("\\s+", " ");
	}
}
