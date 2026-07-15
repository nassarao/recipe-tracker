package com.recipetracker;

import java.text.Normalizer;
import java.util.Locale;

public final class RecipeKey
{
	private RecipeKey()
	{
	}

	public static String of(String outputName, String category, String variant)
	{
		return normalize(category) + ":" + normalize(outputName) + ":" + normalize(variant);
	}

	private static String normalize(String value)
	{
		String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
			.toLowerCase(Locale.ENGLISH)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return normalized.isEmpty() ? "default" : normalized;
	}
}
