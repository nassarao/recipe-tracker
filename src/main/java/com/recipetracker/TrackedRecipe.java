package com.recipetracker;

import java.util.Objects;

public final class TrackedRecipe
{
	private final Recipe recipe;
	private final int quantity;

	public TrackedRecipe(Recipe recipe, int quantity)
	{
		this.recipe = Objects.requireNonNull(recipe);
		this.quantity = Math.max(1, quantity);
	}

	public Recipe getRecipe()
	{
		return recipe;
	}

	public int getQuantity()
	{
		return quantity;
	}
}
