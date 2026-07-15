# Recipe Tracker

Recipe Tracker is a RuneLite Plugin Hub-style plugin for choosing a craftable item and comparing its requirements with the items you are carrying.

## Features

- Type ahead against the live OSRS Wiki instead of downloading a giant recipe catalog.
- Fetch and locally cache only recipes that you select.
- Add **Track materials** beside RuneLite's native **Check materials** menu option and fetch the selected output's Wiki recipe.
- Right-click any item to request its Wiki recipe. The menu entry can be set to **Always**, **Shift + right-click**, or **Disabled** in the plugin settings.
- Set a target output quantity; multi-output recipes such as cannonballs round up correctly.
- See `inventory / required` counts in both the RuneLite sidebar and an in-game overlay.
- Completed material rows turn green as soon as the inventory contains enough.
- Track several outputs at once, including reusable tools.
- Inventory, equipment, rune-pouch contents, and equipped elemental staves are considered.
- Tracked stable recipe keys and quantities persist between client sessions.
- RuneLite's local item cache supplies canonical IDs and icons.

Live Wiki results require a network connection; previously selected Wiki recipes remain available from the local cache. No recipe catalog is bundled with the plugin. A Wiki search result that is not makeable will be rejected when selected because its page has no supported `Recipe` template.

## Run locally

1. Install a Java 11 JDK.
2. Open this directory as a Gradle project in IntelliJ IDEA, or run `./gradlew run` (`gradlew.bat run` on Windows).
3. Enable **Recipe Tracker** in the developer RuneLite client.

Run the automated checks with `./gradlew test`.

## Recipe sources

The OSRS Wiki API is the sole recipe source. For **Check materials** entries where RuneLite does not expose an item name, Recipe Tracker replays the original action and recovers the exact output name from the game response before requesting that Wiki page. Cached recipes are refreshed when selected; if the Wiki is unavailable, the cached copy is used. API requests run asynchronously and never block the client thread.

## Plugin Hub packaging

The project uses `build=standard` and has no third-party dependencies. Before submission, update the author field in `runelite-plugin.properties`, host the repository publicly, and follow the official RuneLite Plugin Hub submission guide.
