# TODO

## Factions and enemy airships

Goal: island regions belong to factions, and faction airships (real Create Aeronautics ships, built by hand and saved as structures, crewed by NPCs) attack the player.

### 1. Regions
- [ ] Split each layer into large region cells (e.g. 2,048 blocks, ~6x6 island cells), or a noise field for natural borders.
- [ ] Pick each region's faction from world seed + layer salt + region coords, like `IslandMath` does for islands. Allow unclaimed regions.
- [ ] Add `SkyWorld#factionAt(layer, x, z)` so the feature, structures and spawners agree on territory. No saved data needed.

### 2. `sky_faction` datapack registry
Files in `data/<ns>/lost_in_the_sky/sky_faction/`. Fields:
- [ ] layers, weight, minimum distance from spawn
- [ ] look: banner pattern/colors, block palette for its builds
- [ ] members: entity types + gear (like `sky_encounter` entities; missing mods skipped)
- [ ] stance: always hostile, or neutral until provoked
- [ ] ships: list of `sky_airship` entries

### 3. Hook into existing content
- [ ] Optional `factions` filter on `sky_encounter` and `sky_jigsaw`, so outposts, villages and bosses only appear in that faction's regions.
- [ ] Faction members get a data attachment with their faction: members don't fight each other, and they know which player attacked them.

### 4. Reputation
- [ ] Per-player, per-faction score in a player data attachment.
- [ ] Killing members lowers it; quests raise it.
- [ ] Commands + small API so KubeJS and FTB Quests can read/change it.
- [ ] Score controls how often and how hard ships attack.
- [ ] Progression: faction boss drops or reputation levels unlock the next tier's airship parts.

### 5. Airships built like a player would
Workflow:
1. Build a working Aeronautics ship in creative (balloons, burners, propellers, CBC cannons, seats).
2. Add this mod's marker blocks:
   - **Ship Brain**: one per ship, the AI. Its block entity runs the ship.
   - **Crew markers**: on Create seats or deck spots; say which faction NPC goes there (pilot, gunner, guard).
   - **Weapon links**: link each cannon mount to the Brain.
3. Save with a structure block or Create schematic (`.nbt`) into `data/<ns>/structure/airships/`.

Tasks:
- [ ] `sky_airship` registry:
  ```json
  {
    "structure": "mypack:airships/raider_sloop",
    "factions": ["mypack:sky_pirates"],
    "layers": ["lost_in_the_sky:regular"],
    "weight": 10,
    "behavior": "broadside",
    "preferred_range": 40,
    "crew": { "pilot": "minecraft:pillager", "gunner": "minecraft:pillager", "guard": "minecraft:vindicator" },
    "weapons": {
      "ammo": "crate",
      "fire_cone_degrees": 3,
      "aim_error_degrees": 1.5,
      "max_range": 160,
      "big_cannon_charges": 2,
      "reload_ticks": 80
    },
    "loot_table": "mypack:airships/raider_sloop"
  }
  ```
- [ ] Behaviors: `broadside` (circle and fire), `ram`, `bomber` (fly over, drop TNT), `patrol` (ignores you unless provoked).
- [ ] Spawn: place the template in air in loaded chunks ~100 blocks from the player, assemble with `SubLevelAssemblyHelper.assembleBlocks(level, anchor, positions, bounds)` (Sable), then spawn and seat the crew.
- [ ] Ship Brain flight: the ship's own balloons/propellers give lift and thrust, driven through its controls (throttle lever, steering wheel, burners via redstone). The Brain adds small correcting forces with `RigidBodyHandle` (`applyImpulseAtPoint`, `applyTorqueImpulse`) in `SablePrePhysicsTickEvent` to hold altitude and turn.
- [ ] Crew stays seated (walking mobs on moving ships will likely break). Pilot dead: ship drifts or sinks. Gunners dead: cannons stop.
- [ ] Capture: kill the crew and break/take the Brain, and the ship becomes a normal player-controlled Aeronautics ship.
- [ ] Damage is free: cannon fire and explosions break real blocks.
- [ ] Cleanup: delete AI ships with no player within ~256 blocks for a while (never captured ships). Cap AI ships per player and per server.

### 6. AI firing Create Big Cannons
CBC is a required dependency (see AGENTS.md). From the 5.11.7 jar (signatures only, bodies not read yet):
- `CannonMountBlockEntity`: `setYaw(float)`, `setPitch(float)`, `onRedstoneUpdate(boolean, boolean, boolean, boolean, int)` (confirm what each arg means), `getContraption()`.
- `MountedBigCannonContraption` / `MountedAutocannonContraption`: `fireShot(ServerLevel, PitchOrientedContraptionEntity)`.
- `MountedAutocannonContraption#insertItemIntoCannon(ItemStack, boolean)` loads autocannon ammo.
- CBC has built-in Sable compat (recoil on ships, shells hitting ships).

Brain loop per linked cannon:
- [ ] Find a target (player or player's ship) in range.
- [ ] Solve the aim by simulating the shell's path (gravity and drag from the munition's CBC properties); lead moving targets.
- [ ] Turn toward it a little each tick (capped at mount turn speed); subtract the ship's own rotation; never aim through its own deck or balloon.
- [ ] Fire when within `fire_cone_degrees` and loaded; add `aim_error_degrees` (smaller on higher layers).
- [ ] Reload:
  - Autocannon: `insertItemIntoCannon` (easy, do first).
  - Big cannon, quick-firing breech: shell + charges through the breech (medium).
  - Big cannon, sliding breech: needs a cannon loader on the ship (hard, fragile).
  - Load exactly `big_cannon_charges`, so the barrel doesn't burst (CBC `fail(...)`).
- [ ] Ammo mode per ship: `infinite` (cheat) or `crate` (players can loot or blow up the crate to disarm the ship).

### 7. Attack spawner
- [ ] Server tick every N seconds: for each player in the sky (above the lower layer, or riding a ship), get the region's faction and the player's reputation.
- [ ] Roll for an attack, scaled by layer, reputation and time since the last attack; spawn ~100-150 blocks away, out of sight.
- [ ] Optional: faction outposts (`sky_jigsaw`) act as hangars; destroying one stops attacks in that region for a while.

### Spikes to run first
- [ ] `/lits spawnship <structure>` debug command: place a template, assemble it, check it flies when a player takes over. Do burners, propellers and fuel survive? Does placement need a tick before assembly? (Sable's `SubLevelSchematicSerializationContext` may let schematics hold ships directly.)
- [ ] Do seated mobs move with the ship? Do unseated ones get kicked (`EntitySubLevelUtil.kickEntity`)?
- [ ] Can ships be assembled and stay loaded ~100 blocks from the player (`SubLevelTicketLoadingSystem`)?
- [ ] CBC mount on a moving ship: `setYaw`/`setPitch` + fire from a command; load an autocannon and a quick-firing big cannon from code.

### Suggested order
1. Ship spawn spike (`/lits spawnship`).
2. Ship Brain that only holds altitude and flies to a point.
3. Crew markers + seated crew.
4. Weapons (autocannons first, then big cannons) and behaviors.
5. Regions, `sky_faction`, reputation, attack spawner.

### Open questions
- Factions always hostile, or can reputation make some friendly (trading, safe passage)?
- Crew: existing mobs from the pack, or custom faction mobs and models?
