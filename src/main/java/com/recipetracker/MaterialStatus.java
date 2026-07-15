package com.recipetracker;

public final class MaterialStatus
{
	private final int itemId;
	private final String name;
	private final int required;
	private final int inInventory;
	private final boolean tool;

	public MaterialStatus(String name, int required, int inInventory)
	{
		this(-1, name, required, inInventory, false);
	}

	public MaterialStatus(int itemId, String name, int required, int inInventory, boolean tool)
	{
		this.itemId = itemId;
		this.name = name;
		this.required = required;
		this.inInventory = inInventory;
		this.tool = tool;
	}

	public int getItemId()
	{
		return itemId;
	}

	public boolean isTool()
	{
		return tool;
	}

	public String getName()
	{
		return name;
	}

	public int getRequired()
	{
		return required;
	}

	public int getInInventory()
	{
		return inInventory;
	}

	public boolean isComplete()
	{
		return inInventory >= required;
	}
}
