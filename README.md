# Lost in the Sky

A Create Aeronautics addon for NeoForge 1.21.1. It raises the overworld to **2,000 blocks tall** and fills the sky with four layers of floating islands, so there's a reason to build airships.

| Layer | Default height | Feel |
|---|---|---|
| `lower` | ~100 blocks above the ground under each island | Small meadow and birch islands |
| `regular` | ~500 above sea level (y≈563) | Cherry and flower forests, sky outposts |
| `high` | ~800 above sea level (y≈863) | Snowy groves and ice, frost sentinel encounter |
| `final` | ~1,000 above sea level (y≈1063) | Huge Celestial Isles, sky warden encounter |

It doesn't replace the terrain generator, so it works on top of vanilla, **Tectonic**, Biomes O' Plenty and other worldgen mods.

## How it works

| Part | File |
|---|---|
| World height (-64 to 1999) | `data/minecraft/dimension_type/overworld.json` |
| Island blocks | `world/SkyIslandFeature.java`, added to every overworld biome by `neoforge/biome_modifier/add_sky_islands.json` |
| Island shapes | `world/IslandMath.java` (pure Java, no Minecraft classes) |
| Island biomes | `mixin/ChunkAccessMixin.java` wraps the biome resolver every chunk generator uses |
| Shared state | `world/SkyWorld.java` (the feature, the biome mixin and structures all ask it, so they agree) |
| Big dungeons on islands | `world/SkyJigsawStructure.java` (`lost_in_the_sky:sky_jigsaw`) |

The sky is split into square cells with at most one island each, picked from the world seed. Any chunk can work out which islands cross it without loading its neighbours, which is how islands span many chunks without a custom generator. The island feature runs in the last decoration step and only fills air, so it never overwrites mountains, trees or structures.

## Adding content (datapacks or other mods)

Everything is JSON. Put files in a datapack or in a mod's `data/` folder.

### Layers: `data/<ns>/lost_in_the_sky/sky_layer/<name>.json`

```json
{
  "anchor": "sea_level",
  "offset": 500,
  "max_y": 1990,
  "y_jitter": 40,
  "cell_size": 192,
  "chance": 0.6,
  "min_radius": 28,
  "max_radius": 80,
  "top_height": 9,
  "depth": 55
}
```

- `anchor`: `surface` measures `offset` from the terrain under the island (follows Tectonic's mountains); `sea_level` measures from sea level.
- `cell_size`: one island at most per cell. Bigger means islands are farther apart.
- `chance`: chance a cell has an island.
- `max_radius` is clamped to about `cell_size / 1.27` so islands never overlap the edge of the area the game checks.
- `depth`: how far the rocky underside hangs down. `top_height`: how tall the dome on top is.

### Biomes: `data/<ns>/lost_in_the_sky/sky_biome/<name>.json`

```json
{
  "layer": "lost_in_the_sky:regular",
  "biome": "minecraft:cherry_grove",
  "weight": 2,
  "top": { "Name": "minecraft:grass_block" },
  "filler": { "Name": "minecraft:dirt" },
  "core": { "Name": "minecraft:stone" },
  "filler_depth": 3,
  "decorations": [
    { "feature": "minecraft:cherry", "tries": 3, "chance": 0.5 }
  ]
}
```

- `biome` can be vanilla, another mod's, or your own. See `worldgen/biome/celestial_isles.json` for a custom biome with its own sky colour and mob spawns.
- `decorations` are configured features placed on the island's surface (`tries` per chunk).
- Each island picks one biome from its layer by `weight`.

### Bosses and small structures: `data/<ns>/lost_in_the_sky/sky_encounter/<name>.json`

```json
{
  "layer": "lost_in_the_sky:final",
  "chance": 0.1,
  "sky_biomes": [ "lost_in_the_sky:final_celestial" ],
  "template": "minecraft:igloo/top",
  "y_offset": 1,
  "entities": [
    { "type": "cataclysm:ignis", "offset": [0, 0, 0], "persistent": true }
  ]
}
```

- Placed on the centre of an island, at most one encounter per island.
- `template` is optional and can be up to 32x32 blocks.
- Entities from mods that aren't installed are skipped, so you can ship encounters for optional mods.
- Some bosses expect their own arena or structure to work properly. Test each one.

### Big dungeons: `worldgen/structure/<name>.json` with type `lost_in_the_sky:sky_jigsaw`

Same as a vanilla jigsaw structure, plus `layer`. It spawns only where that layer has an island under the chunk centre, and starts on the island's surface:

```json
{
  "type": "lost_in_the_sky:sky_jigsaw",
  "biomes": "#minecraft:is_overworld",
  "step": "surface_structures",
  "terrain_adaptation": "none",
  "spawn_overrides": {},
  "layer": "lost_in_the_sky:high",
  "start_pool": "dungeons_arise:<some_pool>/start",
  "size": 7,
  "y_offset": 1,
  "max_distance_from_center": 80
}
```

Pair it with a `worldgen/structure_set` file for spacing (see `sky_outpost`). Point `start_pool` at another mod's template pool, like When Dungeons Arise, to lift its dungeons into the sky.

## Compatibility notes

- **World height:** this mod overrides `minecraft:overworld`'s dimension type. Turn **off** Tectonic's `increased_height` option and don't use other mods that change overworld height (like Sky Lands), or whichever loads last wins.
- **New worlds only.** Changing world height on an existing world isn't supported.
- **Performance:** a 2,064-block-tall world has about 5x as many chunk sections as vanilla. Most are empty air and cheap, but expect more RAM and bigger saves. Lower the island `chance` if generation is slow.
- **Terrain under the lower layer** comes from the overworld's own generator, so the lower islands follow Tectonic's mountains. `max_y` keeps them below the next layer.

## Building

```bash
./gradlew build      # jar goes to build/libs/
./gradlew runClient  # test in game
```

Requires Java 21. The build pulls in Create, Sable, Create Aeronautics, Create Big Cannons and Ritchie's Projectile Library.

## Status

First version. The code has **not been compiled yet**. It was written without access to the Minecraft sources, so expect a few small fixes on the first build. The parts most likely to need a tweak are the `JigsawPlacement.addPieces(...)` call in `SkyJigsawStructure` and `finalizeSpawn` in `SkyIslandFeature`, which are written for the 1.21.1 signatures. The island math (`IslandMath`) was tested on its own.
