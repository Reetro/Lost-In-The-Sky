package com.reetro.lostinthesky.world;

import com.mojang.serialization.Codec;
import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.data.SkyBiome;
import com.reetro.lostinthesky.data.SkyCaveBiome;
import com.reetro.lostinthesky.data.SkyEncounter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.util.RandomSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the sky islands for one chunk.
 *
 * <p>It runs as a normal feature in the last decoration step of every overworld biome (added by
 * {@code data/lost_in_the_sky/neoforge/biome_modifier/add_sky_islands.json}). It only writes the 16x16
 * columns of its own chunk, so it never fights with the terrain generator, and runs last so ground
 * trees and structures are already placed. That is what makes it work with any worldgen mod.
 */
public class SkyIslandFeature extends Feature<NoneFeatureConfiguration> {
    /** Templates bigger than this could reach outside the area a feature may write to. */
    private static final int MAX_TEMPLATE_SIZE = 32;
    /** How many blocks of the ground under a column a mirrored island copies onto its top. */
    private static final int MIRROR_DEPTH = 5;

    public SkyIslandFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) {
            return false;
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null) {
            return false;
        }

        ChunkPos chunk = new ChunkPos(context.origin());
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        RandomSource random = context.random();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean placedAny = false;
        Foundations foundations = Foundations.of(level, chunk);

        for (SkyWorld.CompiledLayer layer : sky.layers()) {
            // Surface of this layer's islands in each column of the chunk, for decorations.
            int[] tops = new int[256];
            SkyWorld.Island[] owners = new SkyWorld.Island[256];
            CaveSpots caveSpots = new CaveSpots();

            int centerX = minX + 8;
            int centerZ = minZ + 8;
            boolean[] layerPlaced = {false};
            sky.forEachIslandNear(layer, centerX, centerZ, 1, island -> {
                IslandMath.Caves caves = sky.caves(island);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = minX + lx;
                        int z = minZ + lz;
                        IslandMath.Column column = IslandMath.column(island.plan(), island.centerY(), x, z);
                        if (column == null) {
                            continue;
                        }
                        int idx = lx * 16 + lz;
                        int top = fillColumn(level, pos, x, z, column, minY, maxY, island, caves, caveSpots, idx, foundations);
                        // Columns with a cave mouth, a building or nothing left get no trees or flowers.
                        if (top != Integer.MIN_VALUE && (owners[idx] == null || top > tops[idx])) {
                            tops[idx] = top;
                            owners[idx] = island;
                        }
                        layerPlaced[0] = true;
                    }
                }
            });
            if (!layerPlaced[0]) {
                continue;
            }
            placedAny = true;
            decorateCaves(context, level, random, minX, minZ, caveSpots);
            decorate(context, level, random, minX, minZ, tops, owners);
            decorateMirrored(context, level, random, minX, minZ, tops, owners);
            addSnow(level, minX, minZ, tops, owners);
            placeEncounters(sky, layer, level, random, chunk, pos);
        }
        return placedAny;
    }

    /** Block kinds while filling a column. */
    private static final byte OUTSIDE = 0;
    private static final byte ROCK = 1;
    private static final byte CAVE = 2;

    /**
     * Fills one column of an island, top down. Every block with air above it (the top, and any ledge
     * or overhang lower down) gets the biome's top block, then filler, then core; on a mirrored
     * island those come from the ground under the column instead ({@link #groundProfile}). Caves are
     * left as air; if the island has a cave biome, the column's cave floors, ceilings and air are
     * recorded in {@code spots} for {@link #decorateCaves}. Under a sky structure's building, the
     * island is filled up to (and cleared above) the building's floor ({@link Foundations}).
     *
     * @return the highest block placed (for decorations), or Integer.MIN_VALUE if the column is empty,
     *         a cave opens its top or a building stands on it
     */
    private static int fillColumn(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z,
                                  IslandMath.Column column, int minY, int maxY, SkyWorld.Island island,
                                  @Nullable IslandMath.Caves caves, CaveSpots spots, int idx, Foundations foundations) {
        SkyWorld.CompiledBiome biome = island.biome();
        SkyBiome def = biome == null ? null : biome.def();
        BlockState topState = def == null ? Blocks.GRASS_BLOCK.defaultBlockState() : def.top();
        BlockState fillerState = def == null ? Blocks.DIRT.defaultBlockState() : def.filler();
        BlockState coreState = def == null ? Blocks.STONE.defaultBlockState() : def.core();
        int fillerDepth = def == null ? 3 : def.fillerDepth();
        int top = column.surface();
        if (top == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        // A building of a sky structure stands here: its floor sets the ground height.
        int floor = foundations.floor[idx];
        int roof = foundations.roof[idx];
        boolean founded = floor != Integer.MIN_VALUE && Math.abs(floor - top) <= Foundations.RANGE;
        int high = founded ? Math.max(top, floor) : top;
        int caveTop = founded ? Math.min(top, floor) : top;
        BlockState[] mirror = island.groundBiome() == null ? null : groundProfile(level, x, z, column.bottom);

        int bottom = Math.max(column.bottom, minY);
        IslandMath.CaveColumn cave = caves == null ? null : caves.column(x, z);
        boolean recordCaves = cave != null && island.caveBiome() != null;
        // kinds[y - bottom + 1], with an OUTSIDE pad at both ends, for finding cave floors and ceilings.
        byte[] kinds = recordCaves ? new byte[high - bottom + 3] : null;
        boolean topOpen = false;
        boolean first = true;
        int sinceAir = 0; // solid blocks directly above this one
        for (int y = high; y >= bottom; y--) {
            boolean solid = founded
                    ? !(y > floor && y <= roof) && ((y <= floor && y > top) || column.solid(y))
                    : column.solid(y);
            if (!solid) {
                sinceAir = 0;
                continue;
            }
            int depthHere = sinceAir++;
            boolean highest = first;
            first = false;
            if (y > maxY) {
                continue;
            }
            // No caves (not even entrance mouths) in the fill under a building or just below its floor.
            boolean underBuilding = founded && y > caveTop - IslandMath.CAVE_ROOF;
            if (cave != null && !underBuilding && cave.cave(y, caveTop)) {
                topOpen |= highest;
                if (recordCaves) {
                    kinds[y - bottom + 1] = CAVE;
                }
                continue;
            }
            if (recordCaves) {
                kinds[y - bottom + 1] = ROCK;
            }
            pos.set(x, y, z);
            // Only fill air: never overwrite mountains, trees or structures already there.
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            BlockState state;
            if (mirror != null && depthHere < MIRROR_DEPTH && mirror[depthHere] != null) {
                state = mirror[depthHere];
            } else {
                state = depthHere == 0 ? topState : (depthHere <= fillerDepth ? fillerState : coreState);
            }
            // Ores only in the hanging underside, never in the ground players build on.
            if (state == coreState && y < island.centerY()) {
                BlockState ore = island.layer().oreAt(island.plan().seed(), x, y, z);
                if (ore != null) {
                    state = ore;
                }
            }
            level.setBlock(pos, state, 2);
        }
        if (recordCaves) {
            spots.record(idx, island.caveBiome(), kinds, bottom);
        }
        return !topOpen && !founded && top <= maxY ? top : Integer.MIN_VALUE;
    }

    /**
     * The top {@link #MIRROR_DEPTH} blocks of the ground directly under a column (skipping trees,
     * plants and snow layers), for mirrored islands: grass and dirt, sand and sandstone, terracotta,
     * podzol, snow... Entries past the ground's first stone repeat that stone. Null if the ground
     * there is water or ice (the island then uses its sky biome's blocks).
     *
     * @param islandBottom lowest Y the island can reach in this column; the scan starts below it
     */
    @Nullable
    private static BlockState[] groundProfile(WorldGenLevel level, int x, int z, int islandBottom) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int start = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1, islandBottom - 1);
        int end = Math.max(level.getMinBuildHeight(), start - 96);
        for (int y = start; y > end; y--) {
            BlockState state = level.getBlockState(at.set(x, y, z));
            boolean dry = state.getFluidState().isEmpty();
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || (dry && state.canBeReplaced())) {
                continue;
            }
            if (!dry || state.is(BlockTags.ICE)) {
                return null;
            }
            BlockState[] profile = new BlockState[MIRROR_DEPTH];
            profile[0] = state.hasProperty(BlockStateProperties.SNOWY) ? state.setValue(BlockStateProperties.SNOWY, false) : state;
            BlockState stone = state.is(BlockTags.BASE_STONE_OVERWORLD) ? state : null;
            for (int d = 1; d < MIRROR_DEPTH; d++) {
                if (stone != null) {
                    profile[d] = stone;
                    continue;
                }
                BlockState below = level.getBlockState(at.set(x, y - d, z));
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    break;
                }
                profile[d] = below;
                if (below.is(BlockTags.BASE_STONE_OVERWORLD)) {
                    stone = below;
                }
            }
            return profile;
        }
        return null;
    }

    /**
     * Buildings (rigid pieces) of {@code sky_jigsaw} structures in one chunk. Islands are placed after
     * structures, and a building is laid out at one height while the island under it has hills and
     * dips, so the island is shaped to it like vanilla's terrain adaptation: filled up to the
     * building's floor and kept clear above it.
     */
    private static final class Foundations {
        /** Only shape the island when its surface is this close to the floor (else it's another island). */
        static final int RANGE = 16;
        /** Per column: the ground's top Y under the building, and the building's top Y. */
        final int[] floor = new int[256];
        final int[] roof = new int[256];

        static Foundations of(WorldGenLevel level, ChunkPos chunk) {
            Foundations f = new Foundations();
            Arrays.fill(f.floor, Integer.MIN_VALUE);
            Arrays.fill(f.roof, Integer.MIN_VALUE);
            if (!(level instanceof WorldGenRegion region)) {
                return f;
            }
            List<StructureStart> starts;
            try {
                StructureManager structures = region.getLevel().structureManager().forWorldGenRegion(region);
                starts = structures.startsForStructure(chunk, structure -> structure instanceof SkyJigsawStructure);
            } catch (RuntimeException e) {
                LostInTheSky.LOGGER.debug("Couldn't read sky structures in chunk {}", chunk, e);
                return f;
            }
            for (StructureStart start : starts) {
                if (!start.isValid()) {
                    continue;
                }
                for (StructurePiece piece : start.getPieces()) {
                    if (!(piece instanceof PoolElementStructurePiece element)
                            || element.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                        continue;
                    }
                    BoundingBox box = piece.getBoundingBox();
                    // Same rule as jigsaw placement: minY + ground delta is the first free block above ground.
                    int ground = box.minY() + element.getGroundLevelDelta() - 1;
                    for (int x = Math.max(box.minX(), chunk.getMinBlockX()); x <= Math.min(box.maxX(), chunk.getMaxBlockX()); x++) {
                        for (int z = Math.max(box.minZ(), chunk.getMinBlockZ()); z <= Math.min(box.maxZ(), chunk.getMaxBlockZ()); z++) {
                            int idx = (x - chunk.getMinBlockX()) * 16 + (z - chunk.getMinBlockZ());
                            f.floor[idx] = f.floor[idx] == Integer.MIN_VALUE ? ground : Math.min(f.floor[idx], ground);
                            f.roof[idx] = Math.max(f.roof[idx], box.maxY());
                        }
                    }
                }
            }
            return f;
        }
    }

    /**
     * Cave floors, ceilings and air per column of one chunk, for cave decorations. Only for cave
     * islands with a cave biome. If two cave islands share a column, the last one filled wins.
     */
    private static final class CaveSpots {
        final SkyWorld.CompiledCaveBiome[] owners = new SkyWorld.CompiledCaveBiome[256];
        final IntArrayList[] floors = new IntArrayList[256];
        final IntArrayList[] ceilings = new IntArrayList[256];
        final IntArrayList[] air = new IntArrayList[256];

        void record(int idx, SkyWorld.CompiledCaveBiome biome, byte[] kinds, int bottom) {
            IntArrayList floor = new IntArrayList();
            IntArrayList ceiling = new IntArrayList();
            IntArrayList open = new IntArrayList();
            for (int i = 1; i < kinds.length - 1; i++) {
                int y = bottom + i - 1;
                if (kinds[i] != CAVE) {
                    continue;
                }
                open.add(y);
                if (kinds[i - 1] == ROCK) {
                    floor.add(y);
                }
                if (kinds[i + 1] == ROCK) {
                    ceiling.add(y);
                }
            }
            if (open.isEmpty()) {
                return;
            }
            owners[idx] = biome;
            floors[idx] = floor;
            ceilings[idx] = ceiling;
            air[idx] = open;
        }

        @Nullable
        IntArrayList get(SkyCaveBiome.Placement placement, int idx) {
            return switch (placement) {
                case FLOOR -> floors[idx];
                case CEILING -> ceilings[idx];
                case AIR -> air[idx];
            };
        }
    }

    /** Places each cave biome's decorations on its own columns' cave floors, ceilings or air. */
    private static void decorateCaves(FeaturePlaceContext<NoneFeatureConfiguration> context, WorldGenLevel level,
                                      RandomSource random, int minX, int minZ, CaveSpots spots) {
        java.util.Set<SkyWorld.CompiledCaveBiome> present = new java.util.LinkedHashSet<>();
        for (SkyWorld.CompiledCaveBiome owner : spots.owners) {
            if (owner != null) {
                present.add(owner);
            }
        }
        for (SkyWorld.CompiledCaveBiome biome : present) {
            for (SkyWorld.CompiledCaveDecoration decoration : biome.decorations()) {
                for (int t = 0; t < decoration.tries(); t++) {
                    int idx = random.nextInt(256);
                    if (random.nextFloat() >= decoration.chance() || spots.owners[idx] != biome) {
                        continue;
                    }
                    IntArrayList ys = spots.get(decoration.placement(), idx);
                    if (ys == null || ys.isEmpty()) {
                        continue;
                    }
                    BlockPos at = new BlockPos(minX + idx / 16, ys.getInt(random.nextInt(ys.size())), minZ + idx % 16);
                    try {
                        decoration.feature().value().place(level, context.chunkGenerator(), random, at);
                    } catch (RuntimeException e) {
                        LostInTheSky.LOGGER.debug("Sky cave decoration failed at {}", at, e);
                    }
                }
            }
        }
    }

    private static void decorate(FeaturePlaceContext<NoneFeatureConfiguration> context, WorldGenLevel level,
                                 RandomSource random, int minX, int minZ, int[] tops, SkyWorld.Island[] owners) {
        // Each biome present in this chunk gets its decorations tried on its own columns.
        java.util.Set<SkyWorld.CompiledBiome> present = new java.util.LinkedHashSet<>();
        for (SkyWorld.Island owner : owners) {
            // Mirrored islands grow their ground biome's own vegetation instead (decorateMirrored).
            if (owner != null && owner.biome() != null && owner.groundBiome() == null) {
                present.add(owner.biome());
            }
        }
        for (SkyWorld.CompiledBiome biome : present) {
            for (SkyWorld.CompiledDecoration decoration : biome.decorations()) {
                for (int t = 0; t < decoration.tries(); t++) {
                    int idx = random.nextInt(256);
                    if (random.nextFloat() >= decoration.chance()) {
                        continue;
                    }
                    SkyWorld.Island owner = owners[idx];
                    if (owner == null || owner.biome() != biome || owner.groundBiome() != null) {
                        continue;
                    }
                    BlockPos at = new BlockPos(minX + idx / 16, tops[idx] + 1, minZ + idx % 16);
                    try {
                        decoration.feature().value().place(level, context.chunkGenerator(), random, at);
                    } catch (RuntimeException e) {
                        LostInTheSky.LOGGER.debug("Sky decoration failed at {}", at, e);
                    }
                }
            }
        }
    }

    /**
     * Grows a mirrored island's ground biome's own vegetation on it: every placed feature of the
     * biome's vegetal step (trees, grass, flowers, cacti...) runs with its own placement rules (counts,
     * rarity, biome filter), but with heightmaps that only see this island's top. So a forest island
     * gets forest trees at forest density, a desert island cacti and dead bushes.
     */
    private static void decorateMirrored(FeaturePlaceContext<NoneFeatureConfiguration> context, WorldGenLevel level,
                                         RandomSource random, int minX, int minZ, int[] tops, SkyWorld.Island[] owners) {
        java.util.Set<Holder<Biome>> present = new java.util.LinkedHashSet<>();
        for (SkyWorld.Island owner : owners) {
            if (owner != null && owner.groundBiome() != null) {
                present.add(owner.groundBiome());
            }
        }
        ChunkGenerator generator = context.chunkGenerator();
        BlockPos origin = new BlockPos(minX, level.getMinBuildHeight(), minZ);
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        for (Holder<Biome> biome : present) {
            List<HolderSet<PlacedFeature>> steps = generator.getBiomeGenerationSettings(biome).features();
            if (step >= steps.size()) {
                continue;
            }
            for (Holder<PlacedFeature> holder : steps.get(step)) {
                PlacedFeature placed = holder.value();
                IslandPlacement placement = new IslandPlacement(level, generator, placed, minX, minZ, tops, owners, biome);
                try {
                    Stream<BlockPos> positions = Stream.of(origin);
                    for (PlacementModifier modifier : placed.placement()) {
                        positions = positions.flatMap(p -> modifier.getPositions(placement, random, p));
                    }
                    positions.filter(placement::onIsland).forEach(p -> placed.feature().value().place(level, generator, random, p));
                } catch (RuntimeException e) {
                    LostInTheSky.LOGGER.debug("Mirrored sky decoration {} failed", holder, e);
                }
            }
        }
    }

    /**
     * Placement context whose heightmaps see only one mirrored biome's island tops in this chunk
     * (anything else reads as the bottom of the world, which heightmap placement skips).
     */
    private static final class IslandPlacement extends PlacementContext {
        private final int minX;
        private final int minZ;
        private final int[] tops;
        private final SkyWorld.Island[] owners;
        private final Holder<Biome> biome;

        IslandPlacement(WorldGenLevel level, ChunkGenerator generator, PlacedFeature feature, int minX, int minZ,
                        int[] tops, SkyWorld.Island[] owners, Holder<Biome> biome) {
            super(level, generator, Optional.of(feature));
            this.minX = minX;
            this.minZ = minZ;
            this.tops = tops;
            this.owners = owners;
            this.biome = biome;
        }

        private int index(int x, int z) {
            int lx = x - minX;
            int lz = z - minZ;
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
                return -1;
            }
            int idx = lx * 16 + lz;
            SkyWorld.Island owner = owners[idx];
            return owner != null && biome.equals(owner.groundBiome()) ? idx : -1;
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            int idx = index(x, z);
            return idx < 0 ? getMinBuildHeight() : tops[idx] + 1;
        }

        /** True if a final position is just above this biome's island top (not on the ground, not in a cave). */
        boolean onIsland(BlockPos pos) {
            int idx = index(pos.getX(), pos.getZ());
            return idx >= 0 && Math.abs(pos.getY() - (tops[idx] + 1)) <= 3;
        }
    }

    /** Snow on island tops in biomes cold enough to snow (worldgen never runs vanilla's freeze step on them). */
    private static void addSnow(WorldGenLevel level, int minX, int minZ, int[] tops, SkyWorld.Island[] owners) {
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int idx = 0; idx < 256; idx++) {
            if (owners[idx] == null) {
                continue;
            }
            above.set(minX + idx / 16, tops[idx] + 1, minZ + idx % 16);
            if (!level.getBiome(above).value().shouldSnow(level, above)) {
                continue;
            }
            level.setBlock(above, Blocks.SNOW.defaultBlockState(), 2);
            BlockPos below = above.below();
            BlockState ground = level.getBlockState(below);
            if (ground.hasProperty(BlockStateProperties.SNOWY)) {
                level.setBlock(below, ground.setValue(BlockStateProperties.SNOWY, true), 2);
            }
        }
    }

    /** Encounters are placed once, by the chunk that holds the island's centre. */
    private static void placeEncounters(SkyWorld sky, SkyWorld.CompiledLayer layer, WorldGenLevel level,
                                        RandomSource random, ChunkPos chunk, BlockPos.MutableBlockPos pos) {
        sky.forEachIslandNear(layer, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(), 0, island -> {
            SkyWorld.CompiledEncounter encounter = island.encounter();
            if (encounter == null) {
                return;
            }
            int cx = island.plan().centerX();
            int cz = island.plan().centerZ();
            if (cx < chunk.getMinBlockX() || cx > chunk.getMaxBlockX() || cz < chunk.getMinBlockZ() || cz > chunk.getMaxBlockZ()) {
                return;
            }
            Integer surface = sky.surfaceAt(layer, cx, cz);
            if (surface == null) {
                return;
            }
            BlockPos origin = new BlockPos(cx, surface + encounter.def().yOffset(), cz);
            placeEncounter(level, random, encounter, origin);
        });
    }

    private static void placeEncounter(WorldGenLevel level, RandomSource random,
                                       SkyWorld.CompiledEncounter encounter, BlockPos origin) {
        SkyEncounter def = encounter.def();
        if (def.template().isPresent()) {
            Optional<StructureTemplate> template = level.getLevel().getStructureManager().get(def.template().get());
            if (template.isEmpty()) {
                LostInTheSky.LOGGER.warn("Sky encounter {}: template {} not found", encounter.id(), def.template().get());
            } else {
                Vec3i size = template.get().getSize();
                if (size.getX() > MAX_TEMPLATE_SIZE || size.getZ() > MAX_TEMPLATE_SIZE) {
                    LostInTheSky.LOGGER.warn("Sky encounter {}: template is {}x{}, max is {}x{}. Use a sky_jigsaw structure for big builds.",
                            encounter.id(), size.getX(), size.getZ(), MAX_TEMPLATE_SIZE, MAX_TEMPLATE_SIZE);
                } else {
                    BlockPos corner = origin.offset(-size.getX() / 2, 0, -size.getZ() / 2);
                    template.get().placeInWorld(level, corner, corner, new StructurePlaceSettings(), random, 2);
                }
            }
        }

        for (SkyEncounter.EntityEntry entry : def.entities()) {
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entry.type());
            if (type.isEmpty()) {
                LostInTheSky.LOGGER.debug("Sky encounter {}: entity {} not installed, skipped", encounter.id(), entry.type());
                continue;
            }
            for (int n = 0; n < entry.count(); n++) {
                Entity entity = type.get().create(level.getLevel());
                if (entity == null) {
                    continue;
                }
                BlockPos at = origin.offset(entry.offset());
                double spread = entry.count() > 1 ? 1.5 : 0.0;
                entity.moveTo(at.getX() + 0.5 + (random.nextDouble() - 0.5) * spread, at.getY(),
                        at.getZ() + 0.5 + (random.nextDouble() - 0.5) * spread, random.nextFloat() * 360f, 0f);
                if (entity instanceof Mob mob) {
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null);
                    if (entry.persistent()) {
                        mob.setPersistenceRequired();
                    }
                }
                level.addFreshEntityWithPassengers(entity);
            }
        }
    }
}
