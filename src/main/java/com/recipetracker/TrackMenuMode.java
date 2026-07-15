package com.recipetracker;

public enum TrackMenuMode
{
	ALWAYS("Always"),
	SHIFT("Shift + right-click"),
	DISABLED("Disabled");

	private final String label;

	TrackMenuMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
