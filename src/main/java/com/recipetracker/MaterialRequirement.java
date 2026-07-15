package com.recipetracker;

import java.util.Objects;

public final class MaterialRequirement
{
	private final int itemId;
	private final int[] acceptedItemIds;
	private final String name;
	private final double quantityPerCraft;

	public MaterialRequirement(int itemId, String name, int quantity)
	{
		this(itemId, name, quantity, new int[0]);
	}

	public MaterialRequirement(int itemId, String name, int quantity, int... alternativeItemIds)
	{
		this(itemId, name, (double) quantity, alternativeItemIds);
	}

	public MaterialRequirement(int itemId, String name, double quantity, int... alternativeItemIds)
	{
		this.itemId = itemId;
		this.acceptedItemIds = new int[alternativeItemIds.length + 1];
		this.acceptedItemIds[0] = itemId;
		System.arraycopy(alternativeItemIds, 0, acceptedItemIds, 1, alternativeItemIds.length);
		this.name = Objects.requireNonNull(name);
		this.quantityPerCraft = quantity;
	}

	public int[] getAcceptedItemIds()
	{
		return acceptedItemIds.clone();
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getName()
	{
		return name;
	}

	public int getQuantity()
	{
		return (int) Math.ceil(quantityPerCraft);
	}

	public double getQuantityPerCraft()
	{
		return quantityPerCraft;
	}
}
