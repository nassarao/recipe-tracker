package com.recipetracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaterialCalculatorTest
{
	@Test
	public void scalesMaterialsByTargetQuantity()
	{
		Recipe steelBar = new Recipe(2353, "Steel bar", "Smithing", 1,
			new MaterialRequirement(440, "Iron ore", 1),
			new MaterialRequirement(453, "Coal", 2));
		Map<Integer, Integer> inventory = new HashMap<>();
		inventory.put(440, 3);
		inventory.put(453, 5);

		List<MaterialStatus> result = MaterialCalculator.calculate(steelBar, 3, inventory);

		assertEquals(3, result.get(0).getRequired());
		assertTrue(result.get(0).isComplete());
		assertEquals(6, result.get(1).getRequired());
		assertFalse(result.get(1).isComplete());
	}

	@Test
	public void roundsUpBatchesAndFractionalMaterials()
	{
		Recipe batch = new Recipe(2, "Batch", "Test", 4,
			new MaterialRequirement(1, "Ingredient", 0.5));

		List<MaterialStatus> result = MaterialCalculator.calculate(batch, 9,
			Collections.singletonMap(1, 2));

		assertEquals(2, result.get(0).getRequired());
		assertTrue(result.get(0).isComplete());
	}

	@Test
	public void combinesRecipesAndCountsSharedToolsOnce()
	{
		MaterialRequirement hammer = new MaterialRequirement(2347, "Hammer", 1);
		Recipe first = new Recipe(10, "First", "Test", 1,
			new MaterialRequirement(1, "Plank", 2)).withTools(hammer);
		Recipe second = new Recipe(11, "Second", "Test", 1,
			new MaterialRequirement(1, "Plank", 3)).withTools(hammer);
		Map<Integer, Integer> inventory = new HashMap<>();
		inventory.put(1, 7);
		inventory.put(2347, 1);

		List<MaterialStatus> result = MaterialCalculator.calculateAll(Arrays.asList(
			new TrackedRecipe(first, 2), new TrackedRecipe(second, 1)), inventory);

		assertEquals(2, result.size());
		assertEquals(7, result.get(0).getRequired());
		assertEquals(1, result.get(1).getRequired());
		assertTrue(result.get(1).isTool());
	}

	@Test
	public void acceptsAlternativeTools()
	{
		Recipe recipe = new Recipe(20, "Output", "Test", 1).withTools(
			new MaterialRequirement(100, "Saw", 1, 101));

		List<MaterialStatus> result = MaterialCalculator.calculateAll(
			Collections.singletonList(new TrackedRecipe(recipe, 1)),
			Collections.singletonMap(101, 1));

		assertTrue(result.get(0).isComplete());
	}

	@Test
	public void distinguishesInventoryCompletionFromCombinedBankAvailability()
	{
		Recipe recipe = new Recipe(20, "Rune item", "Smithing", 1,
			new MaterialRequirement(2363, "Runite bar", 6));

		List<MaterialStatus> result = MaterialCalculator.calculateAll(
			Collections.singletonList(new TrackedRecipe(recipe, 1)),
			Collections.singletonMap(2363, 2), Collections.singletonMap(2363, 4));

		MaterialStatus status = result.get(0);
		assertEquals(2, status.getInInventory());
		assertEquals(4, status.getInBank());
		assertFalse(status.isComplete());
		assertTrue(status.isAvailable());
	}

	@Test
	public void remainsUnavailableWhenInventoryAndBankAreShort()
	{
		Recipe recipe = new Recipe(20, "Rune item", "Smithing", 1,
			new MaterialRequirement(2363, "Runite bar", 6));

		MaterialStatus status = MaterialCalculator.calculateAll(
			Collections.singletonList(new TrackedRecipe(recipe, 1)),
			Collections.singletonMap(2363, 2), Collections.singletonMap(2363, 3)).get(0);

		assertFalse(status.isComplete());
		assertFalse(status.isAvailable());
	}

	@Test
	public void expandedSubRecipeReplacesIntermediateMaterial()
	{
		Recipe table = new Recipe(100, "Table", "Construction", 1,
			new MaterialRequirement(2, "Plank", 4),
			new MaterialRequirement(3, "Nails", 8));
		Recipe planks = new Recipe(2, "Plank", "Woodcutting", 1,
			new MaterialRequirement(1, "Logs", 1));
		TrackedRecipe goal = new TrackedRecipe(table, 2);
		goal.addChild(new TrackedRecipe(planks, 8));

		List<MaterialStatus> result = MaterialCalculator.calculateAll(
			Collections.singletonList(goal), Collections.emptyMap());

		assertEquals(2, result.size());
		assertEquals("Logs", result.get(0).getName());
		assertEquals(8, result.get(0).getRequired());
		assertEquals("Nails", result.get(1).getName());
		assertEquals(16, result.get(1).getRequired());
	}

	@Test
	public void recursiveExpansionHonorsBatchOutputsAndCountsToolsOnce()
	{
		MaterialRequirement hammer = new MaterialRequirement(2347, "Hammer", 1);
		Recipe fixture = new Recipe(100, "Fixture", "Construction", 1,
			new MaterialRequirement(4819, "Bronze nails", 20)).withTools(hammer);
		Recipe nails = new Recipe(4819, "Bronze nails", "Smithing", 15,
			new MaterialRequirement(2349, "Bronze bar", 1)).withTools(hammer);
		Recipe bar = new Recipe(2349, "Bronze bar", "Smithing", 1,
			new MaterialRequirement(436, "Copper ore", 1),
			new MaterialRequirement(438, "Tin ore", 1));
		TrackedRecipe root = new TrackedRecipe(fixture, 1);
		TrackedRecipe nailGoal = new TrackedRecipe(nails, 20);
		nailGoal.addChild(new TrackedRecipe(bar, 2));
		root.addChild(nailGoal);

		List<MaterialStatus> result = MaterialCalculator.calculateAll(
			Collections.singletonList(root), Collections.emptyMap());

		assertEquals(3, result.size());
		assertEquals("Copper ore", result.get(0).getName());
		assertEquals(2, result.get(0).getRequired());
		assertEquals("Tin ore", result.get(1).getName());
		assertEquals(2, result.get(1).getRequired());
		assertEquals("Hammer", result.get(2).getName());
		assertEquals(1, result.get(2).getRequired());
	}
}
