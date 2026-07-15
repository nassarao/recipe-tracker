package com.recipetracker;

public enum RecipeSource
{
	BUNDLED_WIKI(1),
	WIKI_CACHE(2),
	HAND_AUTHORED(3),
	NATIVE_CAPTURE(4);

	private final int priority;

	RecipeSource(int priority)
	{
		this.priority = priority;
	}

	int getPriority()
	{
		return priority;
	}
}
