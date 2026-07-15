package com.recipetracker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

final class ItemIndex
{
	private final Map<String, Integer> byName;
	private final Map<Integer, String> byId;

	private ItemIndex(Map<String, Integer> byName, Map<Integer, String> byId)
	{
		this.byName = Collections.unmodifiableMap(byName);
		this.byId = Collections.unmodifiableMap(byId);
	}

	static ItemIndex build(ItemManager itemManager, int itemCount)
	{
		Map<String, Integer> names = new LinkedHashMap<>();
		Map<Integer, String> ids = new LinkedHashMap<>();
		for (int id = 0; id < itemCount; id++)
		{
			try
			{
				ItemComposition composition = itemManager.getItemComposition(id);
				String name = composition == null ? null : composition.getName();
				if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name) || name.startsWith("<"))
				{
					continue;
				}
				int canonical = itemManager.canonicalize(id);
				names.putIfAbsent(normalize(name), canonical);
				ids.putIfAbsent(canonical, name);
			}
			catch (RuntimeException ignored)
			{
				// Sparse/invalid cache IDs are expected.
			}
		}
		return new ItemIndex(names, ids);
	}

	int findId(String name)
	{
		return byName.getOrDefault(normalize(name), -1);
	}

	String findName(int id)
	{
		return byId.get(id);
	}

	private static String normalize(String name)
	{
		return name == null ? "" : name.replace('\u00a0', ' ').trim().toLowerCase(Locale.ENGLISH);
	}
}
