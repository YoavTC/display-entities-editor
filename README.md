# Display Entities Editor

![https://fabricmc.net/](https://raw.githubusercontent.com/intergrav/devins-badges/refs/heads/v3/assets/cozy/requires/fabric-api_vector.svg)

A Fabric mod for **Minecraft 26.3** that adds in-world gizmo controls for editing display entities
(`block_display`, `item_display` and `text_display`): move, rotate and scale them by dragging handles in the world.

## Showcase

[![a](https://markdown-videos-api.jorgenkh.no/youtube/xQs9YN0iAR0?width=320&height=180&filetype=jpeg)](https://youtu.be/xQs9YN0iAR0)

## Disclaimers

- This mod was inspired by the works of [bikerboys](https://modrinth.com/user/bikerboys), [SlothyUWU](https://modrinth.com/user/SlothyUwU), and [Gm4](https://modrinth.com/user/Gm4), and their projects, respectively [Pivot](https://modrinth.com/mod/pivot), [Sloths Display Entity Editor](https://modrinth.com/datapack/sloths-display-entity-editor), and [Survival Displays](https://modrinth.com/datapack/survival-displays).
- This mod was coded entirely by an AI agent. Just wanna be transparent about that

## Config

Config file available at `config/display_entities_editor.json` with the following options:

```json
{
  "wand_item": "minecraft:breeze_rod",
  "allow_non_operators": true,
  "line_width": 1.5
}
```

- `wand_item`: the client and the server each read their own copy, so keep them the same on a multiplayer server.
- `allow_non_operators` (default `true`): whether players without operator permission may edit displays. This is read by the server. Set it to `false` to require operator permission.
- `line_width` (default `1.5`, range 0.5 - 10): base width of the lines the in-world editor draws (axes, rings, outlines). Read by the client; the thicker hover and drag highlights scale with it.

## Text displays

The text you give a text display (when creating one from an item frame, and in the quick menu's **Change Text**) works
like `/tellraw`: plain text stays plain, and anything starting with `{`, `[` or `"` is read as a JSON text component
(SNBT works too), so you get colours, bold, italic, underlined, strikethrough, obfuscated, fonts, `extra` parts and so on:

```
Hello
{"text":"Hello","bold":true,"color":"gold"}
["Plain ",{"text":"red and italic","color":"red","italic":true}]
{"text":"Custom colour","color":"#ff55aa","shadow_color":-16777216}
```

Invalid components are refused with a chat message.

The create screen (item frame -> Text Display) and the edit screen (sneak-click a text display -> **Edit Text & Options**)
are the same screen: besides the text it has **Background** (on/off) and **Background RGB** (hex colour), **Shadow**,
**Alignment**, **Billboard** (fixed / vertical / horizontal / center) and **Text opacity** (0-255). When editing, every
field starts with the display's current value. A button opens the
[raw JSON text generator](https://www.gamergeeks.net/apps/minecraft/raw-json-text-format-generator) in your browser
(after a confirmation). Billboard can also be cycled for block and item displays from the quick menu.

Text is flat, so a text display has no depth handle when scaling and its selection box is paper thin.

## Deleting displays

Deleting a display (the Delete key or the quick menu) only drops an item frame for displays this mod created from an item frame. Those carry the entity tag `display_entities_editor.from_frame`, which is saved with the entity. Displays without the tag, such as ones summoned by command, are simply removed and drop nothing.

## Building
```
./gradlew build            # jar in build/libs
./gradlew runClient        # dev client
./gradlew runClientGameTest
```

`runClientGameTest` launches a client and exercises the mod end to end (selection, dragging, rotating, scaling, the
quick menu, frame conversion, head textures and the server-side checks). Screenshots are written to
`build/run/clientGameTest/screenshots`.
