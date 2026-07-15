package com.recipetracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.Scrollable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

public class RecipeTrackerPanel extends PluginPanel
{
	private static final Color COMPLETE = new Color(64, 196, 99);
	private static final Color MISSING = new Color(255, 115, 105);

	private final RecipeTrackerPlugin plugin;
	private List<Recipe> recipes;
	private final ItemManager itemManager;
	private final DefaultListModel<RecipeSearchEntry> listModel = new DefaultListModel<>();
	private final JList<RecipeSearchEntry> recipeList = new JList<>(listModel);
	private final JTextField searchField = new JTextField();
	private final JPanel trackedRows = verticalPanel();
	private final JPanel requirementRows = verticalPanel();
	private final Timer wikiSearchTimer;
	private boolean changingSelection;

	RecipeTrackerPanel(RecipeTrackerPlugin plugin, List<Recipe> recipes, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.recipes = recipes;
		this.itemManager = itemManager;
		wikiSearchTimer = new Timer(300, e -> requestWikiResults());
		wikiSearchTimer.setRepeats(false);
		setLayout(new BorderLayout(0, 10));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel chooser = new JPanel(new BorderLayout(0, 6));
		chooser.setOpaque(false);
		JLabel heading = new JLabel("Recipe Tracker");
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
		chooser.add(heading, BorderLayout.NORTH);

		searchField.setToolTipText("Search craftable items; click one to track it");
		searchField.putClientProperty("JTextField.placeholderText", "Search item...");
		chooser.add(searchField, BorderLayout.CENTER);

		recipeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		recipeList.setCellRenderer(new RecipeRenderer());
		JScrollPane scroll = new JScrollPane(recipeList);
		scroll.setPreferredSize(new Dimension(0, 160));
		chooser.add(scroll, BorderLayout.SOUTH);
		add(chooser, BorderLayout.NORTH);

		JPanel details = scrollableVerticalPanel();
		details.add(sectionTitle("Tracked items"));
		details.add(trackedRows);
		details.add(Box.createVerticalStrut(10));
		details.add(sectionTitle("Combined requirements"));
		details.add(requirementRows);
		JLabel note = new JLabel("<html><small>Tools are reusable and are only required once.</small></html>");
		note.setForeground(Color.GRAY);
		details.add(Box.createVerticalStrut(6));
		details.add(note);
		JScrollPane detailScroll = new JScrollPane(details,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		detailScroll.setBorder(BorderFactory.createEmptyBorder());
		detailScroll.setOpaque(false);
		detailScroll.getViewport().setOpaque(false);
		detailScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(detailScroll, BorderLayout.CENTER);

		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent e) { filterRecipes(); }
			@Override public void removeUpdate(DocumentEvent e) { filterRecipes(); }
			@Override public void changedUpdate(DocumentEvent e) { filterRecipes(); }
		});

		recipeList.addListSelectionListener(e ->
		{
			RecipeSearchEntry entry = recipeList.getSelectedValue();
			if (!e.getValueIsAdjusting() && !changingSelection && entry != null)
			{
				if (entry.isWikiLookup()
					|| entry.getRecipe().getSource() == RecipeSource.WIKI_CACHE
					|| entry.getRecipe().getSource() == RecipeSource.BUNDLED_WIKI)
				{
					plugin.trackWikiRecipe(entry.getName(), entry.getRecipe());
				}
				else
				{
					plugin.trackRecipe(entry.getRecipe());
				}
				changingSelection = true;
				recipeList.clearSelection();
				changingSelection = false;
			}
		});
		filterRecipes();
	}

	void updateTracking(List<TrackedRecipe> tracked, List<MaterialStatus> statuses)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> updateTracking(tracked, statuses));
			return;
		}

		trackedRows.removeAll();
		if (tracked.isEmpty())
		{
			trackedRows.add(message("Nothing is being tracked."));
		}
		for (TrackedRecipe item : tracked)
		{
			trackedRows.add(createTrackedRow(item));
			trackedRows.add(Box.createVerticalStrut(3));
		}

		requirementRows.removeAll();
		if (statuses.isEmpty())
		{
			requirementRows.add(message("Right-click a skill-guide item, or choose one above."));
		}
		for (MaterialStatus status : statuses)
		{
			requirementRows.add(createRequirementRow(status));
			requirementRows.add(Box.createVerticalStrut(3));
		}
		trackedRows.revalidate();
		trackedRows.repaint();
		requirementRows.revalidate();
		requirementRows.repaint();
	}

	void setRecipes(List<Recipe> updatedRecipes)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setRecipes(updatedRecipes));
			return;
		}
		this.recipes = updatedRecipes;
		filterRecipes();
	}

	private JPanel createTrackedRow(TrackedRecipe tracked)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel itemName = new JLabel(tracked.getRecipe().getName());
		setItemIcon(itemName, tracked.getRecipe().getOutputItemId());
		row.add(itemName, BorderLayout.CENTER);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		controls.setOpaque(false);
		JSpinner quantity = new JSpinner(new SpinnerNumberModel(tracked.getQuantity(), 1, 10000, 1));
		quantity.setPreferredSize(new Dimension(48, 25));
		quantity.addChangeListener(e -> plugin.setTargetQuantity(tracked.getRecipe(), (Integer) quantity.getValue()));
		JButton remove = new JButton("X");
		remove.setToolTipText("Stop tracking");
		remove.setPreferredSize(new Dimension(28, 25));
		remove.addActionListener(e -> plugin.untrackRecipe(tracked.getRecipe()));
		controls.add(quantity);
		controls.add(remove);
		row.add(controls, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		return row;
	}

	private JPanel createRequirementRow(MaterialStatus status)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		Color color = status.isComplete() ? COMPLETE : MISSING;
		String suffix = status.isTool() ? " (tool)" : "";
		JLabel name = new JLabel(status.getName() + suffix);
		if (status.getItemId() > 0)
		{
			setItemIcon(name, status.getItemId());
		}
		name.setForeground(color);
		JLabel quantity = new JLabel(status.getInInventory() + " / " + status.getRequired());
		quantity.setForeground(color);
		quantity.setFont(quantity.getFont().deriveFont(Font.BOLD));
		row.add(name, BorderLayout.CENTER);
		row.add(quantity, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		return row;
	}

	private void filterRecipes()
	{
		String query = searchField.getText().trim().toLowerCase(Locale.ENGLISH);
		changingSelection = true;
		listModel.clear();
		for (Recipe recipe : recipes)
		{
			boolean queryMatches = query.isEmpty()
				|| recipe.getName().toLowerCase(Locale.ENGLISH).contains(query);
			if (queryMatches)
			{
				listModel.addElement(RecipeSearchEntry.local(recipe));
			}
		}
		recipeList.clearSelection();
		changingSelection = false;
		wikiSearchTimer.restart();
	}

	private void requestWikiResults()
	{
		String query = searchField.getText().trim();
		if (query.length() < 2)
		{
			return;
		}
		searchField.setToolTipText("Searching the OSRS Wiki...");
		plugin.searchWikiRecipes(query, titles -> applyWikiResults(query, titles));
	}

	private void applyWikiResults(String requestedQuery, List<String> titles)
	{
		if (!searchField.getText().trim().equals(requestedQuery))
		{
			return;
		}
		searchField.setToolTipText("Search craftable items; Wiki recipes load when selected");
		for (String title : titles)
		{
			boolean duplicate = false;
			for (int i = 0; i < listModel.size(); i++)
			{
				if (listModel.get(i).getName().equalsIgnoreCase(title))
				{
					duplicate = true;
					break;
				}
			}
			if (!duplicate)
			{
				listModel.addElement(RecipeSearchEntry.wiki(title, plugin.findItemId(title)));
			}
		}
	}

	private static JPanel verticalPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	private static JPanel scrollableVerticalPanel()
	{
		ScrollablePanel panel = new ScrollablePanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	private static JLabel sectionTitle(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
		return label;
	}

	private static JLabel message(String text)
	{
		JLabel label = new JLabel("<html><small>" + text + "</small></html>");
		label.setForeground(Color.GRAY);
		return label;
	}

	private void setItemIcon(JLabel label, int itemId)
	{
		if (itemId <= 0)
		{
			label.setIcon(null);
			return;
		}
		label.putClientProperty("recipeTrackerItemId", itemId);
		AsyncBufferedImage source;
		try
		{
			source = itemManager.getImage(itemId);
		}
		catch (RuntimeException ignored)
		{
			label.setIcon(null);
			return;
		}
		Runnable update = () ->
		{
			Object currentId = label.getClientProperty("recipeTrackerItemId");
			if (!(currentId instanceof Integer) || ((Integer) currentId) != itemId)
			{
				return;
			}
			Image scaled = source.getScaledInstance(20, 20, Image.SCALE_SMOOTH);
			label.setIcon(new ImageIcon(scaled));
			label.revalidate();
			label.repaint();
		};
		update.run();
		source.onLoaded(() -> SwingUtilities.invokeLater(update));
	}

	private class RecipeRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
				isSelected, cellHasFocus);
			RecipeSearchEntry recipe = (RecipeSearchEntry) value;
			label.setText(recipe.getName());
			label.setIcon(null);
			setItemIcon(label, recipe.getItemId());
			label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			return label;
		}
	}

	private static final class ScrollablePanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(16, visibleRect.height - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
