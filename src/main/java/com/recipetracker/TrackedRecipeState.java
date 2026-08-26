package com.recipetracker;

import java.util.ArrayList;
import java.util.List;

final class TrackedRecipeState
{
	String recipeKey;
	int quantity = 1;
	boolean collapsed;
	List<TrackedRecipeState> children = new ArrayList<>();
}
