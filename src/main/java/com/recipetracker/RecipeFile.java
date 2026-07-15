package com.recipetracker;

import java.util.ArrayList;
import java.util.List;

final class RecipeFile
{
	int schemaVersion = 1;
	List<RecipeDefinition> recipes = new ArrayList<>();
}
