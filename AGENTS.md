# Lost in the Sky: context for AI assistants

## What this is
A NeoForge 1.21.1 mod (Java 21), an addon for Create Aeronautics. It raises the overworld to 2,000 blocks tall and adds four layers of floating sky islands, so players have a reason to build airships. It's for Justin's own modpack (details below).

- Mod id: `lost_in_the_sky` · Package: `com.reetro.lostinthesky` · Author: Reetro · License: MIT
- Build setup is copied from the sibling repo `../create-lost-in-the-dark` (ModDevGradle 2.0.144, NeoForge 21.1.248, parchment 2024.11.17). Keep the two in sync.
- Create Aeronautics' internal mod id is `simulated` (not `aeronautics`). It needs `sable`.

## Status
- First version. It was written without access to Minecraft sources, so expect a few small fixes at runtime.
- API calls that were the likeliest to break on 1.21.1 (they compile now, but check them in game):
  - `JigsawPlacement.addPieces(...)` in `world/SkyJigsawStructure.java` (args: DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING, PoolAliasLookup.EMPTY)
  - `Mob.finalizeSpawn(...)` in `world/SkyIslandFeature.java`
  - `Registry#getHolder`, `registryOrThrow` in `world/SkyWorld.java` (1.21.1 names; 1.21.2+ renamed them)
- Compiles (`./gradlew compileJava`) as of 2026-11-22; not yet run in game.
- `world/IslandMath.java` is pure Java and was tested on its own: no island reaches past the 3x3 cell neighbourhood (about 59M checks with the Perlin shapes).

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
- `world/IslandMath`: the sky is split into square cells (`cell_size`), with at most one island per cell, decided by world seed + layer salt + cell coordinates. Each island has a dome-shaped top and a ragged inverted-cone underside, shaped with 2D Perlin noise (`IslandMath#perlin`/`perlinFbm`): a domain-warped outline scaled to the island's size (bulges out at most `EDGE_WOBBLE` 0.22, bites in up to 0.45), a random squash along one axis (aspect 0.6-1, so some islands are oval), Perlin hills on top, and lumps plus ridged-noise spikes underneath. Max radius is clamped to `cellSize / 1.27`, so a 3x3 cell search always finds every island touching a column. Bounds checked in a standalone run (~59M columns): nothing past `reach()` or the 3x3 cells, top at most 1.58x the layer's `top_height`, underside at most 1.5x its `depth` (callers allow 2x).
- `world/SkyWorld`: one per server, built lazily from datapack registries and reset on `ServerStoppedEvent`. It resolves and caches islands, and works out the terrain height for `surface` layers from the overworld's own `ChunkGenerator.getBaseHeight` (follows Tectonic). The feature, biome mixin and structure all ask it, so they agree on where islands are.
- No island sits directly over the layer below it: an island whose footprint (seen from above) overlaps an island in the next layer down is dropped (`SkyWorld#overlapsLowerIsland`, lower layer wins). Only the layer directly below is checked; checking every lower layer kept only ~6% of final islands. With the default JSON, islands per 1000x1000 blocks: lower ~4.9, regular ~4.0, high ~2.1, final ~1.1 (simulated with `IslandMath`). Layers two or more apart (e.g. final over lower) can overlap.
- `world/SkyIslandFeature`: a feature added to all `#minecraft:is_overworld` biomes at step `top_layer_modification`, via `neoforge/biome_modifier/add_sky_islands.json`. It only writes its own chunk's 16x16 columns, only fills air, then places decorations and encounters. Encounters are placed by the chunk holding the island centre; templates are capped at 32x32.
- `mixin/ChunkAccessMixin` + `world/SkyBiomeResolver`: `@ModifyVariable` on `ChunkAccess#fillBiomesFromNoise` wraps the BiomeResolver so island blocks get their sky biome. It only applies to chunks at least 1,024 blocks tall, since a chunk doesn't know its dimension.
- `mixin/PlayerRespawnLogicMixin`: players never spawn on islands. It caps the heightmap reads in `PlayerRespawnLogic#getOverworldRespawnPos` to just below the lowest island underside (`SkyWorld#lowestIslandBottomAt`) and rejects any result still in or on an island. That method handles the world spawn, first join and bedless respawns. Beds and respawn anchors placed on islands still work.
- `mixin/ChunkSkyLightSourcesMixin` + `world/IslandShadows`: islands cast no shadow on the ground. Each chunk's `ChunkSkyLightSources` stores the lowest island underside per column (`SkyWorld#lowestIslandBottomAt`), and any edge at or above it doesn't block sky light, so the column's sky light source drops to the real ground. Trade-off: island caves and roofed builds on islands count as open sky. The client can't compute undersides (no seed or generator), so the server sends `network/IslandShadowPayload` on `ChunkWatchEvent.Sent` and the client rebuilds that chunk's sources (`client/IslandShadowsClient`). Not yet tested in game.
- `mixin/ServerLevelMixin`: `/locate biome` finds sky islands. Vanilla `ServerLevel#findClosestBiome3d` only asks the generator's biome source, which never has island biomes, so this also searches the island grid (`SkyWorld#findClosestIsland`, nearest island centre whose sky biome matches, in growing cell rings). Sky-only biomes skip vanilla's slow search; biomes/tags that can also be on the ground run both and the nearer wins. Not yet tested in game.
- Caves: each island rolls `cave_chance` (per layer); cave islands get winding "spaghetti" tunnels (two 3D Perlin fields near zero, `IslandMath#caveAt`) plus occasional chambers, carved in `SkyIslandFeature#fillColumn` before ores. Tunnels keep a 4-block roof but can open through the sides and underside; surface entrances are shafts in small patches of the top (`IslandMath#entranceFrom`, coarse + fine Perlin masks) that drop to the nearest cave within 32 blocks, about 3x3 blocks wide, roughly 1 per lower island up to ~10 per final island. Entrance columns get no decorations. About 15% of a cave island is hollow (wide tunnels plus big chambers). Because of `ChunkSkyLightSourcesMixin`, island caves get full sky light (no mob spawns by day).
- `world/SkyJigsawStructure` (`lost_in_the_sky:sky_jigsaw`): a jigsaw structure with a `layer` field that starts on an island's surface. Optional `island_center` (start only in the chunk holding an island's centre, at that centre), `min_island_radius` and `island_chance` (one fixed roll per island, salted by layer + start pool) keep spread-out structures on the island; use them with a structure set of spacing 1 / separation 0 so every chunk is a candidate. Islands are features, placed after structures, so terrain-following jigsaw pieces (village streets) would land on the ground: `world/SkyStructureContext` is set while a sky structure lays out pieces, `mixin/JigsawPlacerMixin` then takes child heights from the island surface (`getFirstFreeHeight` in `JigsawPlacement$Placer#tryPlacingChildren`), and `mixin/GravityProcessorMixin` snaps blocks of those pieces to the island surface at placement (when a block would drop >24 blocks below its laid-out height). Off the island edge, pieces stay level with the village. Not yet tested in game.
- Datapack registries (`data/SkyRegistries`), files in `data/<ns>/lost_in_the_sky/<registry>/`:
  - `sky_layer`: anchor, offset, max_y, y_jitter, cell_size, chance, min/max_radius, top_height, depth, cave_chance
  - `sky_biome`: layer, biome, weight, top/filler/core block states, filler_depth, decorations (configured features)
  - `sky_encounter`: layer, chance, sky_biomes filter, optional template, y_offset, entities (missing mods are skipped)
  - `sky_ore`: layer, block, chance (fraction of underside stone), vein_size (1-27). Ores only go in the biome's core block below the island's centre Y (the hanging underside). Veins come from `IslandMath#oreAt` (one vein at most per 4x4x4 cell, from island seed + position, so they line up across chunks). Defaults get more valuable with height: lower coal/copper/iron, regular iron/gold/redstone/lapis/Create zinc, high gold/lapis/iron/emerald, final gold/lapis/emerald and diamond (diamond only on final, the top layer). Missing blocks are skipped.
- Default content: 4 layers, 7 sky biomes, one custom biome per layer so `/locate biome` can find each (`lost_in_the_sky:lower_sky_islands`, `regular_sky_islands`, `high_sky_islands` (cold, snows), `final_sky_islands`; all sky biomes of a layer use its biome and differ only in blocks/decorations), 2 example encounters (vanilla mobs), a `sky_outpost` structure on the regular layer, and plains villages (`sky_village_lower`, `sky_village_regular`: vanilla `minecraft:village/plains/town_centers`, island-centred, islands of radius 60+ / 80+, `island_chance` 0.4: about 22% of lower and 25% of regular islands, ~1 village per 1000x1000 blocks each). Cave chance: lower 0.3, regular 0.4, high 0.5, final 0.6.

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
