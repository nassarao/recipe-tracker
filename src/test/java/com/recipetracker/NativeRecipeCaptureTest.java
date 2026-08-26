package com.recipetracker;

import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeRecipeCaptureTest
{
	@Test
	public void parsesShipwrightMaterialsMessage()
	{
		RecipeDefinition definition = NativeRecipeCapture.parseSailingMaterials(
			"Bronze cannon materials: Plank x 4, Bronze nails x 16, Bronze bar x 8.")
			.orElseThrow(AssertionError::new);

		assertEquals("Bronze cannon", definition.name);
		assertEquals("Sailing", definition.category);
		assertEquals(3, definition.requirements.size());
		assertEquals("Plank", definition.requirements.get(0).name);
		assertEquals(16.0, definition.requirements.get(1).quantity, 0.001);
	}

	@Test
	public void acceptsLargeQuantitiesAndLeadingAnd()
	{
		RecipeDefinition definition = NativeRecipeCapture.parseSailingMaterials(
			"Large hull materials: Oak plank x 1,250, and Steel nails x 2,000")
			.orElseThrow(AssertionError::new);

		assertEquals(1250.0, definition.requirements.get(0).quantity, 0.001);
		assertEquals(2000.0, definition.requirements.get(1).quantity, 0.001);
	}

	@Test
	public void createsStableVariantKeysFromTheExactMaterials()
	{
		Optional<RecipeDefinition> first = NativeRecipeCapture.parseSailingMaterials(
			"Hull materials: Plank x 4, Nails x 10");
		Optional<RecipeDefinition> second = NativeRecipeCapture.parseSailingMaterials(
			"Hull materials: Plank x 8, Nails x 20");

		assertTrue(first.isPresent());
		assertTrue(second.isPresent());
		assertFalse(first.get().key.equals(second.get().key));
	}

	@Test
	public void rejectsPartialOrUnrelatedMessages()
	{
		assertFalse(NativeRecipeCapture.parseSailingMaterials("You need more materials.").isPresent());
		assertFalse(NativeRecipeCapture.parseSailingMaterials(
			"Hull materials: Plank x 4, something unclear").isPresent());
	}
}
