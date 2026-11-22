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
    /** How far the island edge can bite inward, as a fraction of its radius. */
    private static final double INWARD_WOBBLE = 0.45;
    /** Narrowest an island gets across its short axis, as a fraction of its long axis. */
    private static final double MIN_ASPECT = 0.6;
    /** Domain-warp strength for the outline, in noise cells. */
    private static final double WARP = 0.35;
    /** Solid blocks kept above any cave, under an island's surface. */
    private static final int CAVE_ROOF = 4;
    /** Tunnel radius in noise units (bigger = wider tunnels). */
    private static final double TUNNEL_WIDTH = 0.13;
    /** Deepest a surface entrance shaft goes looking for a cave. */
    private static final int SHAFT_MAX_DEPTH = 32;
    /** Coarse entrance mask: higher = fewer areas with entrances. */
    private static final double ENTRANCE_AREA = 0.45;
    /** Fine entrance mask: higher = fewer, smaller holes. */
    private static final double ENTRANCE_HOLE = 0.55;
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
                       int topHeight, int depth, int yJitterOffset, double biomeRoll) {
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

    /** Fractal Perlin: {@code octaves} layers, each twice the detail and half the strength. In [-1, 1]. */
    public static double perlinFbm(long seed, double x, double z, int octaves) {
        double sum = 0.0;
        double amp = 1.0;
        double norm = 0.0;
        double freq = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * perlin(seed + i * 1013L, x * freq, z * freq);
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
        return new Plan(mix(h + 5), cellX, cellZ, centerX, centerZ, radius, top, depth, jitter,
                unit(mix(h + 6)));
    }

    /** Furthest an island's edge can reach from its centre, edge wobble included. */
    public static double reach(Plan p) {
        return p.radius() * (1.0 + EDGE_WOBBLE);
    }

    /** True if the two islands' footprints (seen from above) come within {@code gap} blocks of each other. */
    public static boolean footprintsOverlap(Plan a, Plan b, int gap) {
        double dx = a.centerX() - b.centerX();
        double dz = a.centerZ() - b.centerZ();
        double min = reach(a) + reach(b) + gap;
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
     * Solid range of an island in one block column.
     *
     * @param centerY the island's resting height (layer height + jitter)
     * @param out     out[0] = top Y, out[1] = bottom Y (both inclusive)
     * @return false if the column misses the island
     */
    public static boolean column(Plan p, int centerY, int x, int z, int[] out) {
        double dx = x - p.centerX();
        double dz = z - p.centerZ();
        double r = p.radius();
        if (dx * dx + dz * dz >= r * r * (1.0 + EDGE_WOBBLE) * (1.0 + EDGE_WOBBLE)) {
            return false;
        }
        long seed = p.seed();

        // Squash each island along a random axis, so some are oval. Squashing only ever shrinks the
        // footprint, so reach() and the 3x3 cell search stay valid.
        double angle = unit(mix(seed + 21)) * Math.PI;
        double aspect = MIN_ASPECT + (1.0 - MIN_ASPECT) * unit(mix(seed + 22));
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double u = dx * cos + dz * sin;
        double v = (dz * cos - dx * sin) / aspect;
        double dist = Math.sqrt(u * u + v * v);

        // Outline: domain-warped fractal Perlin, scaled to the island so big and small ones get the
        // same kind of lobes and bays. Outward bulges are capped at EDGE_WOBBLE; inward bites go deeper.
        double scale = Math.max(12.0, r * 0.55);
        double wx = x / scale + WARP * perlin(seed + 31, x / scale, z / scale);
        double wz = z / scale + WARP * perlin(seed + 32, x / scale, z / scale);
        double edge = perlinFbm(seed, wx, wz, 3);
        double effective = r * (1.0 + (edge > 0 ? EDGE_WOBBLE : INWARD_WOBBLE) * edge);
        if (dist >= effective) {
            return false;
        }
        double d = dist / effective; // 0 at centre, 1 at edge

        // Rolling hills on top (at most +0.45 x topHeight, inside the 2 x topHeight the layer allows for).
        double hills = perlinFbm(seed + 7, x / (scale * 0.6), z / (scale * 0.6), 3);
        int top = centerY + (int) Math.round(p.topHeight() * (1.0 - d * d)
                + Math.max(1.5, p.topHeight() * 0.45) * hills);

        // Underside: an inverted cone with broad lumps, plus narrow hanging spikes (ridged noise).
        // Deepest possible is 1.45 x depth, inside the 2 x the layer's depth that callers allow for.
        double lumps = perlinFbm(seed + 13, x / (scale * 0.4), z / (scale * 0.4), 3);
        double ridge = 1.0 - Math.abs(perlin(seed + 17, x / 6.0, z / 6.0));
        double spikes = Math.pow(ridge, 6.0);
        double hang = p.depth() * (Math.pow(1.0 - d, 1.5) * (0.75 + 0.4 * lumps) + 0.3 * spikes * (1.0 - d));
        int bottom = centerY - 2 - (int) Math.round(hang);
        if (top <= bottom) {
            top = bottom + 1;
        }
        out[0] = top;
        out[1] = bottom;
        return true;
    }
}
