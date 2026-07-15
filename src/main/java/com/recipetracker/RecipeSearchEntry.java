package com.recipetracker;

final class RecipeSearchEntry
{
	private final String name;
	private final String category;
	private final int itemId;
	private final Recipe recipe;

	private RecipeSearchEntry(String name, String category, int itemId, Recipe recipe)
	{
		this.name = name;
		this.category = category;
		this.itemId = itemId;
		this.recipe = recipe;
	}

	static RecipeSearchEntry local(Recipe recipe)
	{
		return new RecipeSearchEntry(recipe.getName(), recipe.getCategory(), recipe.getOutputItemId(), recipe);
	}

	static RecipeSearchEntry wiki(String title, int itemId)
	{
		return new RecipeSearchEntry(title, "Wiki result", itemId, null);
	}

	String getName() { return name; }
	String getCategory() { return category; }
	int getItemId() { return itemId; }
	Recipe getRecipe() { return recipe; }
	boolean isWikiLookup() { return recipe == null; }
}
