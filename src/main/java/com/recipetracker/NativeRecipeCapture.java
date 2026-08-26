package com.recipetracker;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NativeRecipeCapture
{
	private static final Pattern MATERIALS_MESSAGE = Pattern.compile(
		"^(.+?)\\s+materials:\\s*(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MATERIAL = Pattern.compile(
		"^(.+?)\\s+x\\s+([0-9][0-9,]*)$", Pattern.CASE_INSENSITIVE);

	private NativeRecipeCapture()
	{
	}

	static Optional<RecipeDefinition> parseSailingMaterials(String message)
	{
		if (message == null)
		{
			return Optional.empty();
		}

		Matcher messageMatcher = MATERIALS_MESSAGE.matcher(message.trim());
		if (!messageMatcher.matches())
		{
			return Optional.empty();
		}

		String outputName = messageMatcher.group(1).trim();
		if (outputName.isEmpty())
		{
			return Optional.empty();
		}

		RecipeDefinition definition = new RecipeDefinition();
		definition.name = outputName;
		definition.category = "Sailing";
		definition.outputQuantity = 1;
		StringBuilder variant = new StringBuilder();

		// Split only on commas that introduce another material. Quantity grouping
		// commas (for example "1,250") must remain part of the quantity.
		for (String rawPart : messageMatcher.group(2).split("\\s*,\\s*(?=(?i:and\\s+)?[^0-9])"))
		{
			String part = rawPart.trim().replaceFirst("(?i)^and\\s+", "");
			Matcher materialMatcher = MATERIAL.matcher(part);
			if (!materialMatcher.matches())
			{
				return Optional.empty();
			}

			String materialName = materialMatcher.group(1).trim();
			int quantity;
			try
			{
				quantity = Integer.parseInt(materialMatcher.group(2).replace(",", ""));
			}
			catch (NumberFormatException ex)
			{
				return Optional.empty();
			}
			if (materialName.isEmpty() || quantity <= 0)
			{
				return Optional.empty();
			}

			RecipeDefinition.RequirementDefinition requirement =
				new RecipeDefinition.RequirementDefinition();
			requirement.name = materialName;
			requirement.quantity = quantity;
			definition.requirements.add(requirement);
			if (variant.length() > 0)
			{
				variant.append('-');
			}
			variant.append(materialName.toLowerCase(Locale.ENGLISH)).append('-').append(quantity);
		}

		if (definition.requirements.isEmpty())
		{
			return Optional.empty();
		}
		definition.variant = variant.toString();
		definition.key = RecipeKey.of(definition.name, definition.category, definition.variant);
		return Optional.of(definition);
	}
}
