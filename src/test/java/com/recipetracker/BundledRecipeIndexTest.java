package com.recipetracker;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BundledRecipeIndexTest
{
	@Test
	public void repositoryLoadsBundledRecipes()
	{
		RecipeRepository repository = new RecipeRepository(new Gson());
		repository.initialize();

		assertTrue(repository.recipes().size() >= 4500);
		Recipe oakLarder = repository.find(8234, "Oak larder");
		assertNotNull(oakLarder);
		assertEquals("Oak larder", oakLarder.getName());
	}

	@Test
	public void containsBroadRecipeConstructionAndSailingCoverage() throws Exception
	{
		RecipeFile file;
		try (InputStream stream = BundledRecipeIndexTest.class.getResourceAsStream(
			"/com/recipetracker/recipes.json"))
		{
			assertNotNull(stream);
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
			{
				file = new Gson().fromJson(reader, RecipeFile.class);
			}
		}

		assertNotNull(file);
		assertEquals(1, file.schemaVersion);
		assertTrue(file.recipes.size() >= 4500);

		RecipeDefinition oakLarder = find(file, "Oak larder");
		assertEquals("Construction", oakLarder.category);
		assertEquals(8.0, requirement(oakLarder, "Oak plank").quantity, 0.001);
		assertEquals("tool", requirement(oakLarder, "Saw").type);

		RecipeDefinition bronzeCannon = find(file, "Bronze cannon");
		assertEquals("Sailing", bronzeCannon.category);
		assertEquals(16.0, requirement(bronzeCannon, "Bronze nails").quantity, 0.001);

		RecipeDefinition bronzeNails = find(file, "Bronze nails");
		assertEquals(15, bronzeNails.outputQuantity);
	}

	private static RecipeDefinition find(RecipeFile file, String name)
	{
		return file.recipes.stream()
			.filter(recipe -> name.equals(recipe.name))
			.findFirst()
			.orElseThrow(AssertionError::new);
	}

	private static RecipeDefinition.RequirementDefinition requirement(
		RecipeDefinition recipe, String name)
	{
		return recipe.requirements.stream()
			.filter(requirement -> name.equals(requirement.name))
			.findFirst()
			.orElseThrow(AssertionError::new);
	}
}
