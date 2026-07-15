package com.recipetracker;

import java.util.ArrayList;
import java.util.List;

final class RecipeDefinition
{
	String key;
	String name;
	String category;
	String variant = "";
	String source;
	int outputItemId;
	int outputQuantity = 1;
	List<RequirementDefinition> requirements = new ArrayList<>();

	static final class RequirementDefinition
	{
		String name;
		int itemId;
		double quantity = 1;
		String type = "material";
		List<String> alternatives = new ArrayList<>();
		List<Integer> alternativeItemIds = new ArrayList<>();
	}
}
