# Display Entities Editor

![https://fabricmc.net/](https://raw.githubusercontent.com/intergrav/devins-badges/refs/heads/v3/assets/cozy/requires/fabric-api_vector.svg)

A Fabric mod for **Minecraft 26.3** that adds in-world gizmo controls for editing display entities
(`block_display`, `item_display` and `text_display`): move, rotate and scale them by dragging handles in the world.

## Showcase

[![a](https://markdown-videos-api.jorgenkh.no/youtube/xQs9YN0iAR0?width=320&height=180&filetype=jpeg)](https://youtu.be/xQs9YN0iAR0)

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

## Building
```
./gradlew build            # jar in build/libs
./gradlew runClient        # dev client
./gradlew runClientGameTest
```

`runClientGameTest` launches a client and exercises the mod end to end (selection, dragging, rotating, scaling, the
quick menu, frame conversion, head textures and the server-side checks). Screenshots are written to
`build/run/clientGameTest/screenshots`.
