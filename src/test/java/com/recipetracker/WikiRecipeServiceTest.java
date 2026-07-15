package com.recipetracker;

import java.util.Optional;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WikiRecipeServiceTest
{
	private final WikiRecipeService service = new WikiRecipeService(new OkHttpClient());

	@Test
	public void parsesCurrentWikiRecipeShape()
	{
		String wiki = "==Creation==\n{{Recipe\n"
			+ "|skill1 = Magic\n|mat1 = Sapphire ring\n|mat2 = Cosmic rune\n"
			+ "|mat3 = Water rune\n|output1 = Ring of recoil\n}}";

		Optional<RecipeDefinition> parsed = service.parse("Ring of recoil", wiki);

		assertTrue(parsed.isPresent());
		assertEquals("Ring of recoil", parsed.get().name);
		assertEquals("Magic", parsed.get().category);
		assertEquals(3, parsed.get().requirements.size());
		assertEquals("Sapphire ring", parsed.get().requirements.get(0).name);
	}

	@Test
	public void preservesBatchAndFractionalQuantities()
	{
		String wiki = "{{Recipe|skill1=Cooking|mat1=Flour|mat1quantity=0.5"
			+ "|output1=Bread|output1quantity=2|tools=[[Range]], [[Cooking pot]]}}";

		RecipeDefinition parsed = service.parse("Bread", wiki).orElseThrow(AssertionError::new);

		assertEquals(2, parsed.outputQuantity);
		assertEquals(0.5, parsed.requirements.get(0).quantity, 0.001);
		assertEquals("tool", parsed.requirements.get(1).type);
	}
}
