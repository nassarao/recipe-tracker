package com.recipetracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Recipe
{
	private final int outputItemId;
	private final String key;
	private final RecipeSource source;
	private final String name;
	private final String category;
	private final int outputQuantity;
	private final List<MaterialRequirement> materials;
	private final List<MaterialRequirement> tools;

	public Recipe(int outputItemId, String name, String category, int outputQuantity,
		MaterialRequirement... materials)
	{
		this(RecipeKey.of(name, category, ""), RecipeSource.HAND_AUTHORED,
			outputItemId, name, category, outputQuantity,
			Collections.unmodifiableList(Arrays.asList(materials)), Collections.emptyList());
	}

	Recipe(String key, RecipeSource source, int outputItemId, String name, String category, int outputQuantity,
		List<MaterialRequirement> materials, List<MaterialRequirement> tools)
	{
		this.key = Objects.requireNonNull(key);
		this.source = Objects.requireNonNull(source);
		this.outputItemId = outputItemId;
		this.name = Objects.requireNonNull(name);
		this.category = Objects.requireNonNull(category);
		this.outputQuantity = outputQuantity;
		this.materials = materials;
		this.tools = tools;
	}

	public List<MaterialRequirement> getTools()
	{
		return tools;
	}

	public Recipe withTools(MaterialRequirement... requiredTools)
	{
		return new Recipe(key, source, outputItemId, name, category, outputQuantity, materials,
			Collections.unmodifiableList(Arrays.asList(requiredTools)));
	}

	Recipe withResolvedRequirements(int resolvedOutputId, List<MaterialRequirement> resolvedMaterials,
		List<MaterialRequirement> resolvedTools)
	{
		return new Recipe(key, source, resolvedOutputId, name, category, outputQuantity,
			Collections.unmodifiableList(resolvedMaterials), Collections.unmodifiableList(resolvedTools));
	}

	public String getKey()
	{
		return key;
	}

	public RecipeSource getSource()
	{
		return source;
	}

	public int getOutputItemId()
	{
		return outputItemId;
	}

	public String getName()
	{
		return name;
	}

	public String getCategory()
	{
		return category;
	}

	public int getOutputQuantity()
	{
		return outputQuantity;
	}

	public List<MaterialRequirement> getMaterials()
	{
		return materials;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
