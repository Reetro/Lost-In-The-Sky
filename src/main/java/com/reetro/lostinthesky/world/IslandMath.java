package com.reetro.lostinthesky.world;

/**
 * Pure math for sky islands. No Minecraft classes, so it can be unit-tested on its own.
 *
 * <p>The sky is split into square cells. Each cell holds at most one island, decided only by the
 * world seed, the layer and the cell's coordinates. Because of that, any chunk can work out which
 * islands cross it without talking to its neighbours, which is what lets islands span many chunks
 * on top of any terrain generator.
 */
public final class IslandMath {
    /** How far the island edge can wobble outward, as a fraction of its radius. */
    public static final double EDGE_WOBBLE = 0.22;
    /** Narrowest an island gets across its short axis, as a fraction of its long axis. */
    private static final double MIN_ASPECT = 0.5;
    /** Solid blocks kept above any cave, under an island's surface. */
    private static final int CAVE_ROOF = 4;
    /** Tunnel radius in noise units (bigger = wider tunnels). */
    private static final double TUNNEL_WIDTH = 0.13;
    /** Deepest a surface entrance shaft goes looking for a cave. */
    private static final int SHAFT_MAX_DEPTH = 32;
    /** Coarse entrance mask: higher = fewer areas with entrances. */
    private static final double ENTRANCE_AREA = 0.32;
    /** Fine entrance mask: higher = fewer, smaller holes. */
    private static final double ENTRANCE_HOLE = 0.45;
    /** Chamber noise above this is hollow (lower = more and bigger chambers). */
    private static final double CHAMBER_THRESHOLD = 0.4;

    private IslandMath() {
    }

    /** Shape settings for one layer (copied from the layer's JSON). */
    public record LayerShape(int cellSize, double chance, int minRadius, int maxRadius,
                             int topHeight, int depth, int yJitter) {
        /** Largest radius that still fits in a 3x3 cell neighbourhood, edge wobble included. */
        public int safeMaxRadius() {
            return (int) Math.floor(cellSize / (1.0 + EDGE_WOBBLE + 0.05));
        }
    }

    /** One island, before its height is known. */
    public record Plan(long seed, int cellX, int cellZ, int centerX, int centerZ, int radius,
                       int topHeight, int depth, int yJitterOffset, double biomeRoll, Shape shape) {
    }

    /**
     * Per-island shape settings, all rolled from the island seed, so every island has its own
     * character on top of its own noise: some are round blobs, some long and lobed, some flat mesas
     * or terraced, some hang in a sharp spire and some in a shallow bowl.
     *
     * @param angle        squash axis
     * @param aspect       short axis / long axis (MIN_ASPECT..1)
     * @param outlineScale outline noise cell size as a fraction of the radius (small = many small bays)
     * @param warp         domain-warp strength for the outline (high = twisted, lobed shapes)
     * @param octaves      outline detail (2-4)
     * @param inward       how deep bays can bite in, as a fraction of the radius
     * @param topExponent  top profile 1 - d^k (low = dome, high = flat mesa)
     * @param hillAmp      hill height as a fraction of topHeight
     * @param hillScale    hill noise cell size as a fraction of the outline scale
     * @param terrace      terrace step in blocks, or 0 for smooth hills
     * @param coneExponent underside profile (1 - d)^k (low = round bowl, high = sharp spire)
     * @param base         underside depth at the centre before lumps, as a fraction of depth
     * @param lumpAmp      broad underside lumps, as a fraction of depth
     * @param spikeAmp     hanging spikes, as a fraction of depth
     * @param spikeScale   spike noise cell size in blocks (small = thin, dense spikes)
     * @param lobes        the blobs the island is built from, as {u, v, radius, height} (u, v and
     *                     radius in fractions of the island radius, squashed frame; height offset in
     *                     fractions of depth). Lobe 0 is the main body, centred on the island centre
     *                     at height 0 so encounters and structures always start on land. Then body
     *                     lobes (dumbbells, crescents, clusters) and small debris lobes near the rim
     *                     at their own height. Every lobe stays inside the radius (|offset| + lobe
     *                     radius <= 1), so reach() still holds.
     * @param cliff        extra underside thickness kept right up to the outline, as a fraction of
     *                     depth: 0 = the island thins to a lip, higher = a cliff wall at the edge
     * @param carve        3D noise strength (0.1 = smooth and solid, 0.9 = shattered: overhangs, holes
     *                     through the rim, hanging chunks, loose bits)
     * @param carveScale   3D noise cell size as a fraction of the radius (small = porous and craggy,
     *                     large = big bites, arches and split-off chunks)
     * @param thickness    underside depth multiplier (low = chunky and shallow, 1 = deep)
     */
    public record Shape(double angle, double aspect, double outlineScale, double warp, int octaves,
                        double inward, double topExponent, double hillAmp, double hillScale, int terrace,
                        double coneExponent, double base, double lumpAmp, double spikeAmp, double spikeScale,
                        double[][] lobes, double cliff, double carve, double carveScale, double thickness) {

        static Shape roll(long seed) {
            // Body lobes: 1 (40%), 2 (30%), 3 (20%), 4 (10%). Then 0-5 debris lobes: small chunks
            // near the rim at their own height, often detached from the main body.
            double countRoll = unit(mix(seed + 37));
            int body = countRoll < 0.4 ? 1 : countRoll < 0.7 ? 2 : countRoll < 0.9 ? 3 : 4;
            int debris = (int) (unit(mix(seed + 39)) * 6);
            double[][] lobes = new double[body + debris][];
            lobes[0] = new double[]{0.0, 0.0, between(seed + 38, body == 1 ? 0.75 : 0.55, 1.0), 0.0};
            for (int i = 1; i < body; i++) {
                double a = unit(mix(seed + 40 + i * 3L)) * Math.PI * 2.0;
                double offset = between(seed + 41 + i * 3L, 0.35, 0.65);
                double size = Math.min(1.0 - offset, between(seed + 42 + i * 3L, 0.3, 0.65));
                lobes[i] = new double[]{Math.cos(a) * offset, Math.sin(a) * offset, size,
                        between(seed + 43 + i * 3L, -0.12, 0.04)};
            }
            for (int i = body; i < body + debris; i++) {
                long h = seed + 100 + i * 5L;
                double a = unit(mix(h)) * Math.PI * 2.0;
                double offset = between(h + 1, 0.6, 0.9);
                double size = Math.min(1.0 - offset, between(h + 2, 0.05, 0.16));
                // Height offset: from 0.5 x depth below the island to just under its top.
                lobes[i] = new double[]{Math.cos(a) * offset, Math.sin(a) * offset, size,
                        between(h + 3, -0.5, 0.08)};
            }
            return new Shape(
                    unit(mix(seed + 21)) * Math.PI,
                    between(seed + 22, MIN_ASPECT, 1.0),
                    between(seed + 23, 0.3, 0.9),
                    between(seed + 24, 0.15, 0.9),
                    2 + (int) (unit(mix(seed + 25)) * 3),
                    between(seed + 26, 0.3, 0.6),
                    between(seed + 27, 1.3, 4.0),
                    between(seed + 28, 0.15, 0.45),
                    between(seed + 29, 0.35, 0.9),
                    unit(mix(seed + 30)) < 0.2 ? 2 + (int) (unit(mix(seed + 31)) * 3) : 0,
                    between(seed + 32, 0.45, 2.6),
                    between(seed + 33, 0.6, 0.85),
                    between(seed + 34, 0.1, 0.35),
                    between(seed + 35, 0.05, 0.3),
                    between(seed + 36, 4.0, 11.0),
                    lobes,
                    unit(mix(seed + 55)) < 0.35 ? 0.0 : between(seed + 56, 0.05, 0.25),
                    between(seed + 57, 0.1, 0.9),
                    between(seed + 58, 0.12, 0.45),
                    between(seed + 59, 0.4, 1.0));
        }

        private static double between(long h, double min, double max) {
            return min + (max - min) * unit(mix(h));
        }
    }

    // ---------------------------------------------------------------- hashing

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long hash(long seed, long a, long b, long salt) {
        long h = mix(seed ^ 0x9E3779B97F4A7C15L);
        h = mix(h ^ (a * 0x632BE59BD9B4E019L));
        h = mix(h ^ (b * 0x85157AF5L));
        return mix(h ^ salt);
    }

    /** Uniform double in [0, 1). */
    public static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Stable salt for a layer id, so layers don't share island positions. */
    public static long salt(String id) {
        long h = 1125899906842597L;
        for (int i = 0; i < id.length(); i++) {
            h = 31 * h + id.charAt(i);
        }
        return mix(h);
    }

    // ---------------------------------------------------------------- noise

    private static final double[] GRAD_X = {1, -1, 0, 0, 0.70710678, -0.70710678, 0.70710678, -0.70710678};
    private static final double[] GRAD_Z = {0, 0, 1, -1, 0.70710678, 0.70710678, -0.70710678, -0.70710678};

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double gradient(long seed, int ix, int iz, double dx, double dz) {
        int g = (int) (hash(seed, ix, iz, 0x9E57L) & 7);
        return GRAD_X[g] * dx + GRAD_Z[g] * dz;
    }

    /** 2D Perlin (gradient) noise in [-1, 1]. One unit = one lattice cell. */
    public static double perlin(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fz);
        double n00 = gradient(seed, x0, z0, fx, fz);
        double n10 = gradient(seed, x0 + 1, z0, fx - 1, fz);
        double n01 = gradient(seed, x0, z0 + 1, fx, fz - 1);
        double n11 = gradient(seed, x0 + 1, z0 + 1, fx - 1, fz - 1);
        double a = n00 + (n10 - n00) * u;
        double b = n01 + (n11 - n01) * u;
        // Unit gradients peak at ~0.707; scale to roughly [-1, 1].
        return Math.max(-1.0, Math.min(1.0, (a + (b - a) * v) * 1.41421356));
    }

    private static final int[][] GRAD3 = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1},
            {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};

    private static double gradient3(long seed, int ix, int iy, int iz, double dx, double dy, double dz) {
        int[] g = GRAD3[(int) Math.floorMod(hash(seed, ix, iz, 0x3D3DL ^ (iy * 0x9E3779B97F4A7C15L)), 12L)];
        return g[0] * dx + g[1] * dy + g[2] * dz;
    }

    /** 3D Perlin (gradient) noise, roughly in [-1, 1]. One unit = one lattice cell. */
    public static double perlin3(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fy);
        double w = fade(fz);
        double x00 = lerp(u, gradient3(seed, x0, y0, z0, fx, fy, fz), gradient3(seed, x0 + 1, y0, z0, fx - 1, fy, fz));
        double x10 = lerp(u, gradient3(seed, x0, y0 + 1, z0, fx, fy - 1, fz), gradient3(seed, x0 + 1, y0 + 1, z0, fx - 1, fy - 1, fz));
        double x01 = lerp(u, gradient3(seed, x0, y0, z0 + 1, fx, fy, fz - 1), gradient3(seed, x0 + 1, y0, z0 + 1, fx - 1, fy, fz - 1));
        double x11 = lerp(u, gradient3(seed, x0, y0 + 1, z0 + 1, fx, fy - 1, fz - 1), gradient3(seed, x0 + 1, y0 + 1, z0 + 1, fx - 1, fy - 1, fz - 1));
        return lerp(w, lerp(v, x00, x10), lerp(v, x01, x11));
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double lattice(long seed, int ix, int iz) {
        return unit(hash(seed, ix, iz, 0xC0B1CL)) * 2.0 - 1.0;
    }

    /** Catmull-Rom spline through four evenly spaced values; t in [0, 1] runs from b to c. */
    private static double catmullRom(double a, double b, double c, double d, double t) {
        return b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)));
    }

    /**
     * 2D cubic noise in [-1, 1]: random values on a lattice, joined by Catmull-Rom curves over the
     * 4x4 nearest points. Compared with Perlin it makes rounder, blobbier features with sharper
     * contrast between highs and lows, so outlines get fat lobes and deep bays. One unit = one
     * lattice cell.
     */
    public static double cubic(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double[] rows = new double[4];
        for (int j = 0; j < 4; j++) {
            int iz = z0 - 1 + j;
            rows[j] = catmullRom(lattice(seed, x0 - 1, iz), lattice(seed, x0, iz),
                    lattice(seed, x0 + 1, iz), lattice(seed, x0 + 2, iz), fx);
        }
        // Catmull-Rom can overshoot the lattice values a little; clamp so callers keep their bounds.
        return Math.max(-1.0, Math.min(1.0, catmullRom(rows[0], rows[1], rows[2], rows[3], fz)));
    }

    /** Fractal cubic noise: {@code octaves} layers, each twice the detail and half the strength. In [-1, 1]. */
    public static double cubicFbm(long seed, double x, double z, int octaves) {
        double sum = 0.0;
        double amp = 1.0;
        double norm = 0.0;
        double freq = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * cubic(seed + i * 1013L, x * freq, z * freq);
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }

    // ---------------------------------------------------------------- islands

    /** The island in a cell, or null if the cell is empty. */
    public static Plan plan(long worldSeed, long layerSalt, int cellX, int cellZ, LayerShape shape) {
        long h = hash(worldSeed, cellX, cellZ, layerSalt);
        if (unit(h) >= shape.chance()) {
            return null;
        }
        int cs = shape.cellSize();
        int maxR = Math.min(shape.maxRadius(), shape.safeMaxRadius());
        int minR = Math.min(shape.minRadius(), maxR);
        int radius = minR + (int) Math.floor(unit(mix(h + 1)) * (maxR - minR + 1));
        // Keep the centre far enough in from the cell edge that the island never reaches past
        // the neighbouring cells (the 3x3 search in SkyWorld).
        int centerX = cellX * cs + (int) Math.floor(unit(mix(h + 2)) * cs);
        int centerZ = cellZ * cs + (int) Math.floor(unit(mix(h + 3)) * cs);
        int jitter = (int) Math.round((unit(mix(h + 4)) * 2.0 - 1.0) * shape.yJitter());
        // Bigger islands are taller and deeper, so they look heavier.
        double sizeScale = 0.75 + 0.5 * (radius - minR) / Math.max(1.0, maxR - minR);
        int top = Math.max(2, (int) Math.round(shape.topHeight() * sizeScale));
        int depth = Math.max(4, (int) Math.round(shape.depth() * sizeScale));
        long seed = mix(h + 5);
        return new Plan(seed, cellX, cellZ, centerX, centerZ, radius, top, depth, jitter,
                unit(mix(h + 6)), Shape.roll(seed));
    }

    /** Furthest an island's edge can reach from its centre, edge wobble included. */
    public static double reach(Plan p) {
        return p.radius() * (1.0 + EDGE_WOBBLE);
    }

    /**
     * How much an island may hang over one in the layer below: the two are "stacked" (and the upper
     * one dropped) when their centres are closer than this fraction of their combined reach. 1 would
     * forbid any overlap at all; 0.7 lets edges overlap by up to 30% but never centre over centre.
     */
    public static final double STACK_FACTOR = 0.7;

    /** True if island {@code a} sits too directly over island {@code b} (see {@link #STACK_FACTOR}). */
    public static boolean stacked(Plan a, Plan b) {
        double dx = a.centerX() - b.centerX();
        double dz = a.centerZ() - b.centerZ();
        double min = (reach(a) + reach(b)) * STACK_FACTOR;
        return dx * dx + dz * dz < min * min;
    }

    /** True if this island rolled caves (see the layer's {@code cave_chance}). */
    public static boolean hasCaves(Plan p, double caveChance) {
        return unit(mix(p.seed() + 40)) < caveChance;
    }

    /**
     * True if a block inside a cave island is cave (left as air). Winding tunnels where two 3D
     * Perlin fields both cross zero ("spaghetti" caves), plus the occasional larger chamber. The top
     * {@link #CAVE_ROOF} blocks of each column stay solid so the surface is never holed; tunnels may
     * open out through the sides and underside, which makes the entrances.
     */
    public static boolean caveAt(Plan p, int x, int y, int z, int top) {
        if (y > top - CAVE_ROOF) {
            return false;
        }
        long seed = p.seed();
        double a = perlin3(seed + 51, x / 44.0, y / 26.0, z / 44.0);
        double b = perlin3(seed + 52, x / 44.0, y / 26.0, z / 44.0);
        if (a * a + b * b < TUNNEL_WIDTH * TUNNEL_WIDTH) {
            return true;
        }
        return perlin3(seed + 53, x / 38.0, y / 20.0, z / 38.0) > CHAMBER_THRESHOLD;
    }

    /**
     * Surface cave entrance: in a few small patches of a cave island's top (two Perlin masks: a coarse
     * one picks the areas, a fine one the holes), a shaft opens from the surface down to the nearest
     * cave below. Only where a cave is within {@link #SHAFT_MAX_DEPTH} blocks, so there are no
     * dead-end pits.
     *
     * @return the Y the shaft reaches down to (open from there to the top), or Integer.MAX_VALUE for none
     */
    public static int entranceFrom(Plan p, int x, int z, int top, int bottom) {
        long seed = p.seed();
        if (perlin(seed + 60, x / 48.0, z / 48.0) < ENTRANCE_AREA
                || perlin(seed + 61, x / 9.0, z / 9.0) < ENTRANCE_HOLE) {
            return Integer.MAX_VALUE;
        }
        for (int y = top - CAVE_ROOF; y >= Math.max(bottom, top - SHAFT_MAX_DEPTH); y--) {
            if (caveAt(p, x, y, z, top)) {
                return y;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Which ore vein, if any, covers a block. Space is split into 4x4x4 cells; each cell holds at
     * most one vein, a loose cluster in a 3x3x3 box. Only uses the island seed and the position, so
     * veins line up across chunk borders.
     *
     * @param cellChance chance per cell of holding a vein of each ore (summing to at most 1)
     * @param veinSize   average blocks per vein for each ore (1-27)
     * @return index of the ore, or -1 for plain stone
     */
    public static int oreAt(long islandSeed, int x, int y, int z, double[] cellChance, int[] veinSize) {
        int cx = Math.floorDiv(x, 4);
        int cy = Math.floorDiv(y, 4);
        int cz = Math.floorDiv(z, 4);
        long h = hash(islandSeed, cx, cz, 0x0E5L ^ (cy * 0x9E3779B97F4A7C15L));
        double roll = unit(h);
        int pick = -1;
        for (int i = 0; i < cellChance.length; i++) {
            roll -= cellChance[i];
            if (roll < 0) {
                pick = i;
                break;
            }
        }
        if (pick < 0) {
            return -1;
        }
        int ox = cx * 4 + (int) (mix(h + 1) & 1);
        int oy = cy * 4 + (int) (mix(h + 2) & 1);
        int oz = cz * 4 + (int) (mix(h + 3) & 1);
        if (x < ox || x > ox + 2 || y < oy || y > oy + 2 || z < oz || z > oz + 2) {
            return -1;
        }
        return unit(hash(islandSeed, x, z, 0x0E6L ^ (y * 0x9E3779B97F4A7C15L))) < veinSize[pick] / 27.0 ? pick : -1;
    }



    /**
     * One block column of an island, in 3D. Each lobe the column touches adds a piece: a smooth base
     * shape (a top surface, dome to mesa, with hills, over an underside, bowl to spire, with lumps,
     * spikes and an optional cliff rim), which 3D noise then pushes in and out by a different amount
     * at every height. The noise moves the top only gently, so it stays walkable, and the side wall
     * and underside strongly: that makes ledges, overhangs, holes through the rim, arches, hanging
     * chunks and loose bits. How strongly is rolled per island ({@link Shape#carve()}). A block is
     * solid if any piece says so, so overlapping lobes merge instead of cutting into each other.
     *
     * <p>{@link #top} and {@link #bottom} bound every block that can be solid, so they are safe to use
     * as an envelope (biomes, shadows, spawn caps). Use {@link #solid(int)} for the real blocks and
     * {@link #surface()} for the highest one.
     */
    public static final class Column {
        /** Highest Y that can be solid (inclusive). */
        public final int top;
        /** Lowest Y that can be solid (inclusive). */
        public final int bottom;
        /** Per piece: surfaceY, undersideY, topCarve, bottomCarve, wallMargin, wallCarve. */
        private final double[][] pieces;
        private final long seed;
        private final double carveScale;
        private final int x;
        private final int z;

        private Column(int top, int bottom, double[][] pieces, long seed, double carveScale, int x, int z) {
            this.top = top;
            this.bottom = bottom;
            this.pieces = pieces;
            this.seed = seed;
            this.carveScale = carveScale;
            this.x = x;
            this.z = z;
        }

        /** True if the block at this Y is island (before caves). */
        public boolean solid(int y) {
            if (y > top || y < bottom) {
                return false;
            }
            boolean needNoise = false;
            for (double[] c : pieces) {
                double above = c[0] - y; // blocks below the base top surface (+ = inside)
                double below = y - c[1]; // blocks above the base underside (+ = inside)
                // Far enough inside every surface that no noise can carve it: solid, no noise needed.
                if (above >= c[2] && below >= c[3] && c[4] >= c[5]) {
                    return true;
                }
                if (above >= -c[2] && below >= -c[3] && c[4] >= -c[5]) {
                    needNoise = true;
                }
            }
            if (!needNoise) {
                return false;
            }
            double n = carveNoise(y);
            for (double[] c : pieces) {
                if (c[0] - y + c[2] * n >= 0 && y - c[1] + c[3] * n >= 0 && c[4] + c[5] * n >= 0) {
                    return true;
                }
            }
            return false;
        }

        /** Highest solid Y, or {@link Integer#MIN_VALUE} if noise carved the whole column away. */
        public int surface() {
            for (int y = top; y >= bottom; y--) {
                if (solid(y)) {
                    return y;
                }
            }
            return Integer.MIN_VALUE;
        }

        private double carveNoise(int y) {
            double sx = x / carveScale;
            double sy = y / (carveScale * 0.6);
            double sz = z / carveScale;
            double n = perlin3(seed + 70, sx, sy, sz) + 0.5 * perlin3(seed + 71, sx * 2.0, sy * 2.0, sz * 2.0);
            return Math.max(-1.0, Math.min(1.0, n / 1.1));
        }
    }

    /**
     * The island's column at x/z, or null if the column misses the island.
     *
     * @param centerY the island's resting height (layer height + jitter)
     */
    public static Column column(Plan p, int centerY, int x, int z) {
        double dx = x - p.centerX();
        double dz = z - p.centerZ();
        double r = p.radius();
        if (dx * dx + dz * dz >= r * r * (1.0 + EDGE_WOBBLE) * (1.0 + EDGE_WOBBLE)) {
            return null;
        }
        long seed = p.seed();
        Shape s = p.shape();

        // Squash each island along its own axis, so some are long ovals. Squashing only ever shrinks
        // the footprint, so reach() and the 3x3 cell search stay valid.
        double cos = Math.cos(s.angle());
        double sin = Math.sin(s.angle());
        double u = (dx * cos + dz * sin) / r;
        double v = (dz * cos - dx * sin) / s.aspect() / r;

        // 2D noise shared by every lobe.
        double scale = Math.max(10.0, r * s.outlineScale());
        double wx = x / scale + s.warp() * cubic(seed + 31, x / scale, z / scale);
        double wz = z / scale + s.warp() * cubic(seed + 32, x / scale, z / scale);
        // Outline: bays bite in up to the island's own `inward`; bulges go out up to EDGE_WOBBLE.
        double edge = cubicFbm(seed, wx, wz, s.octaves());
        double effective = 1.0 + (edge > 0 ? EDGE_WOBBLE : s.inward()) * edge;
        double hillScale = scale * s.hillScale();
        double hills = cubicFbm(seed + 7, x / hillScale, z / hillScale, 3);
        double lumps = cubicFbm(seed + 13, x / (scale * 0.4), z / (scale * 0.4), 3);
        double spikes = Math.pow(1.0 - Math.abs(cubic(seed + 17, x / s.spikeScale(), z / s.spikeScale())), 6.0);
        double depth = p.depth() * s.thickness();
        double topCarve = s.carve() * Math.max(1.5, p.topHeight() * 0.35);

        // Hard limits, inside the 2 x top_height / 2 x depth (of the layer) that callers allow for.
        int maxTop = centerY + (int) Math.floor(1.55 * Math.max(2, p.topHeight()));
        int minBottom = centerY - 2 - (int) Math.floor(1.55 * p.depth());

        double[][] lobes = s.lobes();
        double[][] pieces = new double[lobes.length][];
        int count = 0;
        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        for (double[] lobe : lobes) {
            double lu = u - lobe[0];
            double lv = v - lobe[1];
            // Distance scaled so 1 = this lobe's edge. Every lobe sits inside the radius, so cutting
            // at 1 + EDGE_WOBBLE keeps everything within reach(), noise included.
            double dist = Math.sqrt(lu * lu + lv * lv) / lobe[2];
            if (dist >= 1.0 + EDGE_WOBBLE) {
                continue;
            }
            double d = dist / effective; // 0 at the lobe centre, 1 at its base outline, >1 outside
            // Smaller lobes are lower on top and hang less, so the main body stays dominant and
            // debris chunks are small lumps, not tall spires.
            double weight = Math.min(1.0, 0.25 + 0.75 * lobe[2]);
            double lobeY = lobe[3] * p.depth();

            // Top: dome (low exponent) to flat mesa (high exponent), plus hills. Past the outline it
            // keeps falling, so only noise can put blocks there (ledges and loose chunks).
            double rise = p.topHeight() * weight * (1.0 - Math.pow(d, s.topExponent()))
                    + Math.max(1.5, p.topHeight() * s.hillAmp()) * hills;
            if (s.terrace() > 0) {
                rise = Math.floor(rise / s.terrace()) * s.terrace();
            }
            // Underside: bowl (low exponent) to spire (high exponent), lumps, hanging spikes, and on
            // some islands a cliff rim so the edge is a wall, not a thin lip.
            double hang = d < 1.0
                    ? depth * weight * (Math.pow(1.0 - d, s.coneExponent()) * (s.base() + s.lumpAmp() * lumps)
                    + s.spikeAmp() * spikes * (1.0 - d) + s.cliff())
                    : -p.depth() * 3.0 * (d - 1.0); // rises fast past the outline
            double surfaceY = centerY + lobeY + rise;
            double undersideY = centerY + lobeY - 2 - hang;
            double bottomCarve = s.carve() * depth * weight * 0.5;
            // Side wall: blocks in from this lobe's noisy outline. Noise pushes it in and out by up
            // to wallCarve blocks, differently at every height.
            double lobeBlocks = r * lobe[2] * effective;
            double wallMargin = (1.0 - d) * lobeBlocks;
            double wallCarve = s.carve() * lobeBlocks * 0.3;

            int pieceTop = Math.min(maxTop, (int) Math.floor(surfaceY + topCarve));
            int pieceBottom = Math.max(minBottom, (int) Math.ceil(undersideY - bottomCarve));
            if (pieceTop < pieceBottom || wallMargin < -wallCarve) {
                continue;
            }
            pieces[count++] = new double[]{surfaceY, undersideY, topCarve, bottomCarve, wallMargin, wallCarve};
            top = Math.max(top, pieceTop);
            bottom = Math.min(bottom, pieceBottom);
        }
        if (count == 0) {
            return null;
        }
        return new Column(top, bottom, java.util.Arrays.copyOf(pieces, count), seed,
                Math.max(8.0, r * s.carveScale()), x, z);
    }

    /**
     * Height range that can hold island blocks in one column (an envelope: some blocks inside it
     * may be air). For biomes, shadows and spawn caps; use {@link #column(Plan, int, int, int)} for
     * real blocks.
     *
     * @param out out[0] = top Y, out[1] = bottom Y (both inclusive)
     * @return false if the column misses the island
     */
    public static boolean column(Plan p, int centerY, int x, int z, int[] out) {
        Column c = column(p, centerY, x, z);
        if (c == null) {
            return false;
        }
        out[0] = c.top;
        out[1] = c.bottom;
        return true;
    }
}
