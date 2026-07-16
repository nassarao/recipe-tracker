package com.recipetracker;

public final class MaterialStatus
{
	private final int itemId;
	private final String name;
	private final int required;
	private final int inInventory;
	private final int inBank;
	private final boolean tool;

	public MaterialStatus(String name, int required, int inInventory)
	{
		this(-1, name, required, inInventory, 0, false);
	}

	public MaterialStatus(int itemId, String name, int required, int inInventory, boolean tool)
	{
		this(itemId, name, required, inInventory, 0, tool);
	}

	public MaterialStatus(int itemId, String name, int required, int inInventory, int inBank, boolean tool)
	{
		this.itemId = itemId;
		this.name = name;
		this.required = required;
		this.inInventory = inInventory;
		this.inBank = inBank;
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

	public int getInBank()
	{
		return inBank;
	}

	public boolean isComplete()
	{
		return inInventory >= required;
	}

	public boolean isAvailable()
	{
		return (long) inInventory + inBank >= required;
	}
}
