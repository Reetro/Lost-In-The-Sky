# Lost in the Sky: context for AI assistants

## What this is
A NeoForge 1.21.1 mod (Java 21), an addon for Create Aeronautics. It raises the overworld to 2,000 blocks tall and adds four layers of floating sky islands, so players have a reason to build airships. It's for Justin's own modpack (details below).

- Mod id: `lost_in_the_sky` · Package: `com.reetro.lostinthesky` · Author: Reetro · License: MIT
- Build setup is copied from the sibling repo `../create-lost-in-the-dark` (ModDevGradle 2.0.144, NeoForge 21.1.248, parchment 2024.11.17). Keep the two in sync.
- Create Aeronautics' internal mod id is `simulated` (not `aeronautics`). It needs `sable`.

## Status
- First version, **never compiled yet**. It was written without access to Minecraft sources, so expect a few small fixes.
- API calls most likely to need fixing for 1.21.1:
  - `JigsawPlacement.addPieces(...)` in `world/SkyJigsawStructure.java` (args: DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING, PoolAliasLookup.EMPTY)
  - `Mob.finalizeSpawn(...)` in `world/SkyIslandFeature.java`
  - `Registry#getHolder`, `registryOrThrow` in `world/SkyWorld.java` (1.21.1 names; 1.21.2+ renamed them)
- `world/IslandMath.java` is pure Java and was tested on its own: no island reaches past the 3x3 cell neighbourhood (about 30M checks).

## Requirements from Justin
- World height about 2,000 blocks. It's set in `data/minecraft/dimension_type/overworld.json`: min_y -64, height 2064, so the top block is y 1999.
- Four layers:
  1. **lower:** about 100 blocks above the ground under each island (anchor `surface`)
  2. **regular:** about 500 above the overworld (sea level + 500)
  3. **high:** about 800 (sea level + 800)
  4. **final:** about 1,000 (sea level + 1000)
- **Must work with any worldgen, especially Tectonic.** Never replace the chunk generator or noise settings.
- A system for adding custom biomes, bosses and structures to sky islands, data-driven so modpacks can add content with JSON.

## Architecture
- `world/IslandMath`: the sky is split into square cells (`cell_size`), with at most one island per cell, decided by world seed + layer salt + cell coordinates. Each island has a dome-shaped top and a ragged inverted-cone underside. Max radius is clamped to `cellSize / 1.27`, so a 3x3 cell search always finds every island touching a column.
- `world/SkyWorld`: one per server, built lazily from datapack registries and reset on `ServerStoppedEvent`. It resolves and caches islands, and works out the terrain height for `surface` layers from the overworld's own `ChunkGenerator.getBaseHeight` (follows Tectonic). The feature, biome mixin and structure all ask it, so they agree on where islands are.
- `world/SkyIslandFeature`: a feature added to all `#minecraft:is_overworld` biomes at step `top_layer_modification`, via `neoforge/biome_modifier/add_sky_islands.json`. It only writes its own chunk's 16x16 columns, only fills air, then places decorations and encounters. Encounters are placed by the chunk holding the island centre; templates are capped at 32x32.
- `mixin/ChunkAccessMixin` + `world/SkyBiomeResolver`: `@ModifyVariable` on `ChunkAccess#fillBiomesFromNoise` wraps the BiomeResolver so island blocks get their sky biome. It only applies to chunks at least 1,024 blocks tall, since a chunk doesn't know its dimension.
- `mixin/PlayerRespawnLogicMixin`: players never spawn on islands. It caps the heightmap reads in `PlayerRespawnLogic#getOverworldRespawnPos` to just below the lowest island underside (`SkyWorld#lowestIslandBottomAt`) and rejects any result still in or on an island. That method handles the world spawn, first join and bedless respawns. Beds and respawn anchors placed on islands still work.
- `world/SkyJigsawStructure` (`lost_in_the_sky:sky_jigsaw`): a jigsaw structure with a `layer` field that starts on an island's surface.
- Datapack registries (`data/SkyRegistries`), files in `data/<ns>/lost_in_the_sky/<registry>/`:
  - `sky_layer`: anchor, offset, max_y, y_jitter, cell_size, chance, min/max_radius, top_height, depth
  - `sky_biome`: layer, biome, weight, top/filler/core block states, filler_depth, decorations (configured features)
  - `sky_encounter`: layer, chance, sky_biomes filter, optional template, y_offset, entities (missing mods are skipped)
- Default content: 4 layers, 7 sky biomes, a custom biome `celestial_isles`, 2 example encounters (vanilla mobs), and a `sky_outpost` structure on the regular layer.

## Known caveats
- Tectonic's `increased_height` option must be **off**, since both mods override the overworld dimension_type. Don't combine with Sky Lands either.
- New worlds only. The tall world means more chunk sections, so more RAM and bigger saves.
- Bosses spawned raw by encounters (e.g. `cataclysm:ignis`) may expect their own arena. Test each one.

## The modpack this mod is for
- NeoForge 1.21.1, built with the CurseForge app (CurseForge-hosted mods only, except this mod; Voxy is downloaded by hand for Roxy).
- Core: Create, Create Aeronautics, AE2 (+ MEGA Cells, Applied Create), Create Big Cannons, Tectonic, this mod.
- Bosses: Mowzie's Mobs, Bosses'Rise, When Dungeons Arise, Bosses of Mass Destruction, L_Ender's Cataclysm.
- Gear: Apotheosis. Tools: KubeJS (recipe gating), FTB Quests.
- Progression: **Ground** (cars and boats only) → **Low Sky Islands** → **High Sky Islands** → **End / Nether**. Boss drops unlock the next tier's airship parts through KubeJS. Plan to lock Nether portal lighting and Eye of Ender until the High Sky stage.
- Idea for later: point `sky_jigsaw` structures at When Dungeons Arise pools, and use encounters for Cataclysm / Bosses'Rise bosses on the high and final layers.

## Suggested next steps
1. `./gradlew build` and fix compile errors.
2. `./gradlew runClient`, make a new world, fly up (`/tp @s ~ 600 ~`) and check islands and biomes (F3).
3. Test with Tectonic installed.
4. Tune layer JSON (size, spacing) and add real boss encounters.
