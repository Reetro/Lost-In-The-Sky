package com.reetro.lostinthesky.world;

import com.mojang.datafixers.util.Pair;
import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.data.SkyBiome;
import com.reetro.lostinthesky.data.SkyEncounter;
import com.reetro.lostinthesky.data.SkyLayer;
import com.reetro.lostinthesky.data.SkyOre;
import com.reetro.lostinthesky.data.SkyRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Everything the sky needs for one running server: the compiled layers/biomes/encounters and a
 * cache of resolved islands. Built lazily from the datapack registries the first time worldgen
 * asks, and thrown away when the server stops.
 *
 * <p>The biome mixin, the island feature and the sky_jigsaw structure all ask this class, so they
 * always agree on where every island is.
 */
public final class SkyWorld {
    /** Dimensions shorter than this never get sky islands (only the raised overworld is this tall). */
    public static final int MIN_SKY_WORLD_HEIGHT = 1024;
    /** Island biomes reach this far above an island's top, so the air just above it uses the island's sky/fog. */
    private static final int BIOME_ABOVE = 12;
    private static final int BIOME_BELOW = 4;
    private static final int CACHE_LIMIT = 250_000;
    /** Minimum open-sky gap (seen from above) between islands of different layers. 0 = they may touch. */
    private static final int STACK_GAP = 0;

    private static volatile SkyWorld instance;

    private final MinecraftServer server;
    private final long seed;
    private final List<CompiledLayer> layers;
    private final Map<Long, Optional<Island>> islandCache = new ConcurrentHashMap<>();
    private volatile boolean warnedNoOverworld;

    // ------------------------------------------------------------------ lifecycle

    /** The sky for the running server, or null when no server is running. */
    @Nullable
    public static SkyWorld get() {
        SkyWorld sky = instance;
        if (sky != null) {
            return sky;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        synchronized (SkyWorld.class) {
            if (instance == null || instance.server != server) {
                instance = compile(server);
            }
            return instance;
        }
    }

    public static void reset() {
        instance = null;
    }

    private SkyWorld(MinecraftServer server, long seed, List<CompiledLayer> layers) {
        this.server = server;
        this.seed = seed;
        this.layers = layers;
    }

    private static SkyWorld compile(MinecraftServer server) {
        RegistryAccess access = server.registryAccess();
        Registry<SkyLayer> layerRegistry = access.registryOrThrow(SkyRegistries.SKY_LAYER);
        Registry<SkyBiome> biomeRegistry = access.registryOrThrow(SkyRegistries.SKY_BIOME);
        Registry<SkyEncounter> encounterRegistry = access.registryOrThrow(SkyRegistries.SKY_ENCOUNTER);
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        Registry<ConfiguredFeature<?, ?>> features = access.registryOrThrow(Registries.CONFIGURED_FEATURE);

        List<CompiledLayer> layers = new ArrayList<>();
        for (Map.Entry<ResourceKey<SkyLayer>, SkyLayer> entry : layerRegistry.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            layers.add(new CompiledLayer(layers.size(), id, entry.getValue(), IslandMath.salt(id.toString())));
        }
        // Lowest layer first, so lookups and logs read bottom-up.
        layers.sort(Comparator.comparingInt(l -> l.def.offset()));
        for (int i = 0; i < layers.size(); i++) {
            layers.set(i, layers.get(i).withIndex(i));
        }

        for (Map.Entry<ResourceKey<SkyBiome>, SkyBiome> entry : biomeRegistry.entrySet()) {
            SkyBiome def = entry.getValue();
            CompiledLayer layer = find(layers, def.layer());
            if (layer == null) {
                LostInTheSky.LOGGER.warn("Sky biome {} names unknown layer {}", entry.getKey().location(), def.layer());
                continue;
            }
            Optional<Holder.Reference<Biome>> biome = biomes.getHolder(def.biome());
            if (biome.isEmpty()) {
                LostInTheSky.LOGGER.warn("Sky biome {} uses unknown biome {}", entry.getKey().location(), def.biome().location());
                continue;
            }
            List<CompiledDecoration> decorations = new ArrayList<>();
            for (SkyBiome.Decoration decoration : def.decorations()) {
                Optional<Holder.Reference<ConfiguredFeature<?, ?>>> feature = features.getHolder(decoration.feature());
                if (feature.isPresent()) {
                    decorations.add(new CompiledDecoration(feature.get(), decoration.tries(), decoration.chance()));
                } else {
                    LostInTheSky.LOGGER.warn("Sky biome {} uses unknown feature {}", entry.getKey().location(), decoration.feature().location());
                }
            }
            layer.biomes.add(new CompiledBiome(entry.getKey().location(), def, biome.get(), decorations));
        }

        for (Map.Entry<ResourceKey<SkyEncounter>, SkyEncounter> entry : encounterRegistry.entrySet()) {
            SkyEncounter def = entry.getValue();
            CompiledLayer layer = find(layers, def.layer());
            if (layer == null) {
                LostInTheSky.LOGGER.warn("Sky encounter {} names unknown layer {}", entry.getKey().location(), def.layer());
                continue;
            }
            layer.encounters.add(new CompiledEncounter(entry.getKey().location(), def));
        }
        for (Map.Entry<ResourceKey<SkyOre>, SkyOre> entry : access.registryOrThrow(SkyRegistries.SKY_ORE).entrySet()) {
            SkyOre def = entry.getValue();
            CompiledLayer layer = find(layers, def.layer());
            if (layer == null) {
                LostInTheSky.LOGGER.warn("Sky ore {} names unknown layer {}", entry.getKey().location(), def.layer());
                continue;
            }
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(def.block());
            if (block.isEmpty()) {
                LostInTheSky.LOGGER.debug("Sky ore {}: block {} not installed, skipped", entry.getKey().location(), def.block());
                continue;
            }
            layer.ores.add(new CompiledOre(entry.getKey().location(), def, block.get().defaultBlockState()));
        }

        // Registry order isn't guaranteed to be stable between runs, but island contents must be.
        for (CompiledLayer layer : layers) {
            layer.ores.sort(Comparator.comparing(o -> o.id.toString()));
            layer.compileOres();
            layer.biomes.sort(Comparator.comparing(b -> b.id.toString()));
            layer.encounters.sort(Comparator.comparing(e -> e.id.toString()));
            layer.totalWeight = layer.biomes.stream().mapToInt(b -> b.def.weight()).sum();
            LostInTheSky.LOGGER.info("Sky layer {}: {} biomes, {} encounters, {} ores", layer.id, layer.biomes.size(), layer.encounters.size(), layer.ores.size());
        }

        long seed = server.getWorldData().worldGenOptions().seed();
        return new SkyWorld(server, seed, List.copyOf(layers));
    }

    @Nullable
    private static CompiledLayer find(List<CompiledLayer> layers, ResourceLocation id) {
        for (CompiledLayer layer : layers) {
            if (layer.id.equals(id)) {
                return layer;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ queries

    public List<CompiledLayer> layers() {
        return layers;
    }

    @Nullable
    public CompiledLayer layer(ResourceLocation id) {
        return find(layers, id);
    }

    /** Biome for a block position inside a sky island, or null to keep the normal biome. */
    @Nullable
    public Holder<Biome> skyBiomeAt(int x, int y, int z) {
        int[] column = new int[2];
        for (CompiledLayer layer : layers) {
            if (layer.biomes.isEmpty() || y < layer.minPossibleY() || y > layer.maxPossibleY()) {
                continue;
            }
            int cs = layer.def.cellSize();
            int cellX = Math.floorDiv(x, cs);
            int cellZ = Math.floorDiv(z, cs);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Island island = island(layer, cellX + ox, cellZ + oz);
                    if (island == null || island.biome == null) {
                        continue;
                    }
                    if (IslandMath.column(island.plan, island.centerY, x, z, column)
                            && y >= column[1] - BIOME_BELOW && y <= column[0] + BIOME_ABOVE) {
                        return island.biome.holder;
                    }
                }
            }
        }
        return null;
    }

    /** Calls {@code action} for every island in {@code layer} that could cross block column x/z's cell neighbourhood. */
    public void forEachIslandNear(CompiledLayer layer, int x, int z, int extraCells, Consumer<Island> action) {
        int cs = layer.def.cellSize();
        int cellX = Math.floorDiv(x, cs);
        int cellZ = Math.floorDiv(z, cs);
        int r = 1 + extraCells;
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                Island island = island(layer, cellX + ox, cellZ + oz);
                if (island != null) {
                    action.accept(island);
                }
            }
        }
    }

    /**
     * Nearest island (by horizontal distance to its centre) whose sky biome matches, within
     * {@code radius} blocks, for {@code /locate biome}. Searches each layer's cell grid in growing
     * rings and stops once no closer island is possible.
     *
     * @return the island centre on its surface and its biome, or null if none is in range
     */
    @Nullable
    public Pair<BlockPos, Holder<Biome>> findClosestIsland(Predicate<Holder<Biome>> biomes, BlockPos from, int radius) {
        Island best = null;
        double bestDist = radius;
        for (CompiledLayer layer : layers) {
            if (layer.biomes.stream().noneMatch(b -> biomes.test(b.holder))) {
                continue;
            }
            int cs = layer.def.cellSize();
            int cellX = Math.floorDiv(from.getX(), cs);
            int cellZ = Math.floorDiv(from.getZ(), cs);
            int maxRing = radius / cs + 2;
            for (int ring = 0; ring <= maxRing; ring++) {
                // Anything in this ring or further is at least (ring - 1) cells away.
                if ((ring - 1) * (double) cs > bestDist) {
                    break;
                }
                for (int ox = -ring; ox <= ring; ox++) {
                    for (int oz = -ring; oz <= ring; oz++) {
                        if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) {
                            continue;
                        }
                        Island island = island(layer, cellX + ox, cellZ + oz);
                        if (island == null || island.biome == null || !biomes.test(island.biome.holder)) {
                            continue;
                        }
                        double dist = Math.hypot(island.plan.centerX() - from.getX(), island.plan.centerZ() - from.getZ());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = island;
                        }
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        Integer top = surfaceAt(best.layer, best.plan.centerX(), best.plan.centerZ());
        int y = top == null ? best.centerY : top + 1;
        return Pair.of(new BlockPos(best.plan.centerX(), y, best.plan.centerZ()), best.biome.holder);
    }

    /** Top block Y of the island in {@code layer} at x/z, or null if there is no island there. */
    @Nullable
    public Integer surfaceAt(CompiledLayer layer, int x, int z) {
        int[] column = new int[2];
        Integer best = null;
        int cs = layer.def.cellSize();
        int cellX = Math.floorDiv(x, cs);
        int cellZ = Math.floorDiv(z, cs);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Island island = island(layer, cellX + ox, cellZ + oz);
                if (island != null && IslandMath.column(island.plan, island.centerY, x, z, column)) {
                    best = best == null ? column[0] : Math.max(best, column[0]);
                }
            }
        }
        return best;
    }

    /**
     * Lowest underside Y of any island (in any layer) over block column x/z, or null if the column
     * is open sky. Used to keep player spawns on the ground below the islands.
     */
    @Nullable
    public Integer lowestIslandBottomAt(int x, int z) {
        int[] column = new int[2];
        Integer lowest = null;
        for (CompiledLayer layer : layers) {
            int cs = layer.def.cellSize();
            int cellX = Math.floorDiv(x, cs);
            int cellZ = Math.floorDiv(z, cs);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Island island = island(layer, cellX + ox, cellZ + oz);
                    if (island != null && IslandMath.column(island.plan, island.centerY, x, z, column)) {
                        lowest = lowest == null ? column[1] : Math.min(lowest, column[1]);
                    }
                }
            }
        }
        return lowest;
    }

    /** The island in one cell of a layer, or null. Results are cached. */
    @Nullable
    public Island island(CompiledLayer layer, int cellX, int cellZ) {
        long key = ((long) layer.index << 58) ^ (((long) cellX & 0x1FFFFFFFL) << 29) ^ ((long) cellZ & 0x1FFFFFFFL);
        Optional<Island> cached = islandCache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (islandCache.size() > CACHE_LIMIT) {
            islandCache.clear();
        }
        Optional<Island> resolved = Optional.ofNullable(resolve(layer, cellX, cellZ));
        islandCache.put(key, resolved);
        return resolved.orElse(null);
    }

    @Nullable
    private Island resolve(CompiledLayer layer, int cellX, int cellZ) {
        IslandMath.Plan plan = IslandMath.plan(seed, layer.salt, cellX, cellZ, layer.shape);
        if (plan == null) {
            return null;
        }
        int base = switch (layer.def.anchor()) {
            case SURFACE -> terrainHeight(plan.centerX(), plan.centerZ());
            case SEA_LEVEL -> seaLevel();
        };
        int centerY = base + layer.def.offset() + plan.yJitterOffset();
        if (centerY > layer.def.maxY()) {
            return null;
        }
        if (overlapsLowerIsland(layer, plan)) {
            return null;
        }

        CompiledBiome biome = null;
        if (layer.totalWeight > 0) {
            int roll = (int) Math.floor(plan.biomeRoll() * layer.totalWeight);
            for (CompiledBiome candidate : layer.biomes) {
                roll -= candidate.def.weight();
                if (roll < 0) {
                    biome = candidate;
                    break;
                }
            }
        }

        CompiledEncounter encounter = null;
        for (int i = 0; i < layer.encounters.size(); i++) {
            CompiledEncounter candidate = layer.encounters.get(i);
            List<ResourceLocation> allowed = candidate.def.skyBiomes();
            if (!allowed.isEmpty() && (biome == null || !allowed.contains(biome.id))) {
                continue;
            }
            double roll = IslandMath.unit(IslandMath.mix(plan.seed() + 1000L + i));
            if (roll < candidate.def.chance()) {
                encounter = candidate;
                break;
            }
        }
        return new Island(layer, plan, centerY, biome, encounter);
    }

    /**
     * Islands never sit directly over the layer below: if this island's footprint would overlap an
     * island in the next layer down, that one wins and this one is dropped. Only the layer directly
     * below is checked (checking every lower layer left the final layer ~94% empty). Lower layers
     * only, so the recursion through {@link #island} always ends at the bottom layer and the result
     * is deterministic.
     */
    private boolean overlapsLowerIsland(CompiledLayer layer, IslandMath.Plan plan) {
        if (layer.index == 0) {
            return false;
        }
        CompiledLayer lower = layers.get(layer.index - 1);
        int cs = lower.def.cellSize();
        int lowerMaxR = Math.min(lower.shape.maxRadius(), lower.shape.safeMaxRadius());
        int range = (int) Math.ceil(IslandMath.reach(plan) + lowerMaxR * (1.0 + IslandMath.EDGE_WOBBLE) + STACK_GAP);
        int minCellX = Math.floorDiv(plan.centerX() - range, cs);
        int maxCellX = Math.floorDiv(plan.centerX() + range, cs);
        int minCellZ = Math.floorDiv(plan.centerZ() - range, cs);
        int maxCellZ = Math.floorDiv(plan.centerZ() + range, cs);
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                Island other = island(lower, cx, cz);
                if (other != null && IslandMath.footprintsOverlap(plan, other.plan, STACK_GAP)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int seaLevel() {
        ServerLevel overworld = server.overworld();
        return overworld == null ? 63 : overworld.getChunkSource().getGenerator().getSeaLevel();
    }

    /**
     * Terrain height from the overworld's own generator, so the "surface" anchor follows whatever
     * terrain mod is installed (vanilla, Tectonic, ...). Only uses noise, never loads chunks.
     */
    private int terrainHeight(int x, int z) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            if (!warnedNoOverworld) {
                warnedNoOverworld = true;
                LostInTheSky.LOGGER.warn("Overworld not ready while placing sky islands; using sea level");
            }
            return 63;
        }
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();
        RandomState randomState = overworld.getChunkSource().randomState();
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, overworld, randomState);
    }

    // ------------------------------------------------------------------ compiled data

    public static final class CompiledLayer {
        public final int index;
        public final ResourceLocation id;
        public final SkyLayer def;
        public final IslandMath.LayerShape shape;
        final long salt;
        final List<CompiledBiome> biomes;
        final List<CompiledEncounter> encounters;
        final List<CompiledOre> ores;
        /** Per ore: chance a 4x4x4 cell holds a vein, and the vein size (see {@link IslandMath#oreAt}). */
        double[] oreCellChance = new double[0];
        int[] oreVeinSize = new int[0];
        int totalWeight;

        CompiledLayer(int index, ResourceLocation id, SkyLayer def, long salt) {
            this(index, id, def, salt, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private CompiledLayer(int index, ResourceLocation id, SkyLayer def, long salt,
                              List<CompiledBiome> biomes, List<CompiledEncounter> encounters, List<CompiledOre> ores) {
            this.index = index;
            this.id = id;
            this.def = def;
            this.shape = def.shape();
            this.salt = salt;
            this.biomes = biomes;
            this.encounters = encounters;
            this.ores = ores;
        }

        CompiledLayer withIndex(int newIndex) {
            return new CompiledLayer(newIndex, id, def, salt, biomes, encounters, ores);
        }

        void compileOres() {
            oreCellChance = new double[ores.size()];
            oreVeinSize = new int[ores.size()];
            for (int i = 0; i < ores.size(); i++) {
                SkyOre def = ores.get(i).def;
                oreVeinSize[i] = def.veinSize();
                // A vein averages veinSize blocks, so this many veins per 64-block cell gives `chance`.
                oreCellChance[i] = def.chance() * 64.0 / def.veinSize();
            }
            double total = java.util.Arrays.stream(oreCellChance).sum();
            if (total > 1.0) {
                LostInTheSky.LOGGER.warn("Sky layer {}: ore chances add up to more than the stone can hold, scaling them down", id);
                for (int i = 0; i < oreCellChance.length; i++) {
                    oreCellChance[i] /= total;
                }
            }
        }

        /** The ore at a block in an island's underside, or null for plain stone. */
        @Nullable
        public BlockState oreAt(long islandSeed, int x, int y, int z) {
            if (ores.isEmpty()) {
                return null;
            }
            int i = IslandMath.oreAt(islandSeed, x, y, z, oreCellChance, oreVeinSize);
            return i < 0 ? null : ores.get(i).state;
        }

        /** Conservative Y range islands of this layer can occupy, for quick rejects. */
        int minPossibleY() {
            return def.anchor() == SkyLayer.Anchor.SURFACE
                    ? -64
                    : -64 + def.offset() - def.yJitter() - def.depth() * 2 - 8;
        }

        int maxPossibleY() {
            return def.anchor() == SkyLayer.Anchor.SURFACE
                    ? def.maxY() + def.topHeight() * 2 + BIOME_ABOVE + 4
                    : 320 + def.offset() + def.yJitter() + def.topHeight() * 2 + BIOME_ABOVE + 4;
        }
    }

    public record CompiledBiome(ResourceLocation id, SkyBiome def, Holder<Biome> holder,
                                List<CompiledDecoration> decorations) {
    }

    public record CompiledDecoration(Holder<ConfiguredFeature<?, ?>> feature, int tries, float chance) {
    }

    public record CompiledEncounter(ResourceLocation id, SkyEncounter def) {
    }

    public record CompiledOre(ResourceLocation id, SkyOre def, BlockState state) {
    }

    /** A placed island: its shape, resting height, biome and optional encounter. */
    public record Island(CompiledLayer layer, IslandMath.Plan plan, int centerY,
                         @Nullable CompiledBiome biome, @Nullable CompiledEncounter encounter) {
    }
}
