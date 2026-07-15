package com.recipetracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
public class WikiRecipeService
{
	private static final String API = "https://oldschool.runescape.wiki/api.php"
		+ "?action=parse&prop=wikitext&format=json&redirects=true&page=";
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)\\]\\]");
	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{[^{}]*\\}\\}");
	private static final Pattern HTML = Pattern.compile("<[^>]+>");
	private final OkHttpClient httpClient;
	private static final String SEARCH_API = "https://oldschool.runescape.wiki/api.php"
		+ "?action=opensearch&namespace=0&limit=30&format=json&search=";

	@Inject
	WikiRecipeService(OkHttpClient httpClient)
	{
		this.httpClient = httpClient;
	}

	CompletableFuture<Optional<RecipeDefinition>> fetch(String outputName, Executor executor)
	{
		return CompletableFuture.supplyAsync(() -> fetchNow(outputName), executor);
	}

	CompletableFuture<List<String>> search(String query, Executor executor)
	{
		return CompletableFuture.supplyAsync(() -> searchNow(query), executor);
	}

	private List<String> searchNow(String query)
	{
		List<String> titles = new ArrayList<>();
		try
		{
			String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
			Request request = new Request.Builder().url(SEARCH_API + encoded)
				.header("User-Agent", "Recipe-Tracker-RuneLite-Plugin/1.0").build();
			try (Response response = httpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null) return titles;
				JsonArray root = new JsonParser().parse(response.body().string()).getAsJsonArray();
				if (root.size() < 2 || !root.get(1).isJsonArray()) return titles;
				for (com.google.gson.JsonElement element : root.get(1).getAsJsonArray())
				{
					titles.add(element.getAsString());
				}
			}
		}
		catch (Exception ignored) { }
		return titles;
	}

	Optional<RecipeDefinition> fetchNow(String outputName)
	{
		try
		{
			String encoded = URLEncoder.encode(outputName.replace(' ', '_'), StandardCharsets.UTF_8.name());
			Request request = new Request.Builder()
				.url(API + encoded)
				.header("User-Agent", "Recipe-Tracker-RuneLite-Plugin/1.0")
				.build();
			try (Response response = httpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					return Optional.empty();
				}
				JsonObject root = new JsonParser().parse(response.body().string()).getAsJsonObject();
				if (!root.has("parse"))
				{
					return Optional.empty();
				}
				String wikitext = root.getAsJsonObject("parse").getAsJsonObject("wikitext").get("*").getAsString();
				return parse(outputName, wikitext);
			}
		}
		catch (Exception ignored)
		{
			return Optional.empty();
		}
	}

	Optional<RecipeDefinition> parse(String requestedName, String wikitext)
	{
		int start = findRecipeStart(wikitext);
		if (start < 0)
		{
			return Optional.empty();
		}
		String block = extractTemplate(wikitext, start);
		if (block == null)
		{
			return Optional.empty();
		}
		Map<String, String> params = parseParameters(block);
		RecipeDefinition definition = new RecipeDefinition();
		definition.name = clean(first(params, "output1txt", "output1", "name"));
		if (definition.name.isEmpty())
		{
			definition.name = requestedName;
		}
		definition.category = clean(first(params, "skill1", "skill2", "skill"));
		if (definition.category.isEmpty())
		{
			definition.category = "Other";
		}
		definition.outputQuantity = integer(first(params, "output1quantity", "outputquantity"), 1);
		definition.key = RecipeKey.of(definition.name, definition.category, "");

		for (int i = 1; i <= 30; i++)
		{
			String material = clean(params.get("mat" + i));
			if (material.isEmpty())
			{
				continue;
			}
			RecipeDefinition.RequirementDefinition requirement = new RecipeDefinition.RequirementDefinition();
			requirement.name = material;
			requirement.quantity = decimal(params.get("mat" + i + "quantity"), 1);
			definition.requirements.add(requirement);
		}

		String tools = clean(first(params, "tools", "tool"));
		for (String tool : tools.split("[,;/+]"))
		{
			String name = tool.trim();
			if (name.isEmpty() || "none".equalsIgnoreCase(name))
			{
				continue;
			}
			RecipeDefinition.RequirementDefinition requirement = new RecipeDefinition.RequirementDefinition();
			requirement.name = name;
			requirement.type = "tool";
			definition.requirements.add(requirement);
		}
		return definition.requirements.isEmpty() ? Optional.empty() : Optional.of(definition);
	}

	private static int findRecipeStart(String text)
	{
		String lower = text.toLowerCase(Locale.ENGLISH);
		int start = lower.indexOf("{{recipe");
		return start;
	}

	private static String extractTemplate(String text, int start)
	{
		int depth = 0;
		for (int i = start; i < text.length() - 1; i++)
		{
			String pair = text.substring(i, i + 2);
			if ("{{".equals(pair))
			{
				depth++;
				i++;
			}
			else if ("}}".equals(pair))
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return text.substring(start, i + 1);
				}
			}
		}
		return null;
	}

	private static Map<String, String> parseParameters(String block)
	{
		Map<String, String> result = new LinkedHashMap<>();
		List<String> parts = splitTopLevel(block.substring(2, block.length() - 2));
		for (int i = 1; i < parts.size(); i++)
		{
			String part = parts.get(i).replace('\n', ' ').trim();
			int equals = part.indexOf('=');
			if (equals > 0)
			{
				result.put(part.substring(0, equals).trim().toLowerCase(Locale.ENGLISH),
					part.substring(equals + 1).trim());
			}
		}
		return result;
	}

	private static List<String> splitTopLevel(String text)
	{
		List<String> result = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < text.length(); i++)
		{
			if (i + 1 < text.length())
			{
				String pair = text.substring(i, i + 2);
				if ("{{".equals(pair) || "[[".equals(pair))
				{
					depth++;
					i++;
					continue;
				}
				if ("}}".equals(pair) || "]]".equals(pair))
				{
					depth--;
					i++;
					continue;
				}
			}
			if (text.charAt(i) == '|' && depth == 0)
			{
				result.add(text.substring(start, i));
				start = i + 1;
			}
		}
		result.add(text.substring(start));
		return result;
	}

	private static String clean(String value)
	{
		if (value == null)
		{
			return "";
		}
		String cleaned = WIKILINK.matcher(value).replaceAll("$1");
		cleaned = TEMPLATE.matcher(cleaned).replaceAll("");
		cleaned = HTML.matcher(cleaned).replaceAll("");
		return cleaned.replace("&amp;", "&").replaceAll("'{2,}", "")
			.replaceAll("\\s+", " ").trim();
	}

	private static String first(Map<String, String> values, String... keys)
	{
		for (String key : keys)
		{
			String value = values.get(key);
			if (value != null && !value.trim().isEmpty())
			{
				return value;
			}
		}
		return "";
	}

	private static int integer(String value, int fallback)
	{
		return (int) Math.max(1, Math.round(decimal(value, fallback)));
	}

	private static double decimal(String value, double fallback)
	{
		if (value == null)
		{
			return fallback;
		}
		Matcher matcher = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(value.replace(",", ""));
		return matcher.find() ? Double.parseDouble(matcher.group()) : fallback;
	}
}
