# Display Entities Editor

A Fabric mod for **Minecraft 26.3** that adds in-world gizmo controls for editing display entities
(`block_display`, `item_display` and `text_display`): move, rotate and scale them by dragging handles in the world
instead of typing `/data merge` commands. The look and feel is inspired by the Axiom mod (this project is not
affiliated with Axiom).

## Requirements
- Minecraft 26.3, Fabric Loader 0.19.5+, [Fabric API](https://modrinth.com/mod/fabric-api) 0.163.0+26.3, Java 25
- Install on the **client and the server** (singleplayer works as is). By default every player can use it; set `allow_non_operators` to `false` in the server's config to require operator permission.

## Getting started
1. Give yourself the wand: `/give @s minecraft:breeze_rod`
2. Hold it. Edit mode is on while the wand is in your main or off hand and off when you put it away.
3. Spawn something to edit: `/summon block_display ~ ~1 ~4 {block_state:"minecraft:tnt"}`
4. Look at it and click to select it, then drag a handle.

The wand item can be changed in `config/display_entities_editor.json`:

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

## Controls
| Input | Action |
|---|---|
| Hold the wand | Edit mode on. With nothing selected, nearby displays are outlined |
| Click a display (right or left button) | Select it |
| Click + drag a handle (either button) | Move (arrows / centre cube), rotate (rings) or scale (cubes) |
| `H` | Cycle mode: Translate / Rotate / Scale |
| `J` | Cycle the scroll nudge amount: 0.01 / 0.1 / 0.25 / 0.5 (shown in the help panel) |
| Scroll over a handle | Nudge by the current amount (blocks for moving, scale units for scaling, and that fraction of a quarter turn for rotating) |
| Hold Ctrl / Shift | Coarser snapping / no snapping |
| `Delete` / `Ctrl`+`Z` | Remove the selected display (drops an item frame) / undo |
| Sneak + click a display | Quick menu (see below) |
| Wand + right-click an empty (glow) item frame | Turn it into a display: choose Text / Block / Item, then pick or type the content |

Cycle mode, cycle nudge, delete and undo are regular keybinds (Options > Controls), and the on-screen help lists your current bindings.

### Quick menu
Sneak + click a display for: **Change Block / Item / Text**, **Reset Scale**, **Reset Rotation**,
**Align Position to Block** and **Delete**. For a player head item display there is also **Set Texture**, which takes
a Base64 texture value or a `textures.minecraft.net` URL and writes it to
`item.components."minecraft:profile".properties[0].value`.

Blocks and items are chosen from a searchable, scrollable browser.

## Safety
The server never trusts the client:
- Every edit needs the wand in hand, plus operator permission when `allow_non_operators` is `false`.
- The player must be within 64 blocks of a display to edit it (12 blocks for the item-frame conversion).
- Scale is capped at 100; non-finite, out-of-world and zero-rotation values are rejected.
- Rate limits: 80 transform updates and 20 actions per second per player.
- Content is validated against the block/item registries. Deleting a display drops one item frame and converting a
  frame consumes it, so neither creates items from nothing.
- The editing ray is 5 blocks long (client side): displays and handles further away can't be picked or hovered. While
  dragging, a ray that would go past 5 blocks keeps steering the drag at the 5-block limit, so movement stays smooth.

## Building
```
./gradlew build            # jar in build/libs
./gradlew runClient        # dev client
./gradlew runClientGameTest
```

`runClientGameTest` launches a client and exercises the mod end to end (selection, dragging, rotating, scaling, the
quick menu, frame conversion, head textures and the server-side checks). Screenshots are written to
`build/run/clientGameTest/screenshots`.

## Project layout
- `src/main` - config, network payloads and the server-side rules (`DisplayEntitiesEditor`), frame conversion, quick actions,
  access widener for `Display`.
- `src/client` - `GizmoController` (picking, handles, drag math, drawing), `GizmoHud`, the menus and browser screens,
  keybinds, and two small mixins (the wand takes over use/attack clicks and mouse scroll while held).
- `src/gametest` - the client game tests.
