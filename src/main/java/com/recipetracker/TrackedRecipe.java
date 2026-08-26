package com.recipetracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TrackedRecipe
{
	private final Recipe recipe;
	private int quantity;
	private boolean collapsed;
	private final List<TrackedRecipe> children = new ArrayList<>();
	private TrackedRecipe parent;

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

	public boolean isCollapsed()
	{
		return collapsed;
	}

	public List<TrackedRecipe> getChildren()
	{
		return Collections.unmodifiableList(children);
	}

	void setQuantity(int quantity)
	{
		this.quantity = Math.max(1, Math.min(10000, quantity));
	}

	void setCollapsed(boolean collapsed)
	{
		this.collapsed = collapsed;
	}

	TrackedRecipe getParent()
	{
		return parent;
	}

	void addChild(TrackedRecipe child)
	{
		child.parent = this;
		children.add(child);
	}

	void removeChild(TrackedRecipe child)
	{
		if (children.remove(child))
		{
			child.parent = null;
		}
	}

	TrackedRecipe childFor(MaterialRequirement material)
	{
		for (TrackedRecipe child : children)
		{
			int outputId = child.recipe.getOutputItemId();
			for (int acceptedId : material.getAcceptedItemIds())
			{
				if (outputId > 0 && outputId == acceptedId)
				{
					return child;
				}
			}
			if (child.recipe.getName().equalsIgnoreCase(material.getName()))
			{
				return child;
			}
		}
		return null;
	}
}
