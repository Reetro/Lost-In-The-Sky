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

    private static double lattice(long seed, int x, int z) {
        return unit(hash(seed, x, z, 0x51A7E)) * 2.0 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** Smooth value noise in [-1, 1]. */
    public static double valueNoise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double tx = smooth(x - x0);
        double tz = smooth(z - z0);
        double a = lattice(seed, x0, z0);
        double b = lattice(seed, x0 + 1, z0);
        double c = lattice(seed, x0, z0 + 1);
        double d = lattice(seed, x0 + 1, z0 + 1);
        double ab = a + (b - a) * tx;
        double cd = c + (d - c) * tx;
        return ab + (cd - ab) * tz;
    }

    /** Two-octave noise in roughly [-1, 1]. */
    public static double fbm(long seed, double x, double z) {
        return (valueNoise(seed, x, z) * 0.67 + valueNoise(seed + 101, x * 2.03, z * 2.03) * 0.33);
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
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist >= p.radius() * (1.0 + EDGE_WOBBLE)) {
            return false;
        }
        double edge = fbm(p.seed(), x / 28.0, z / 28.0);
        double effective = p.radius() * (1.0 + EDGE_WOBBLE * edge);
        if (dist >= effective) {
            return false;
        }
        double d = dist / effective; // 0 at centre, 1 at edge
        double bumps = fbm(p.seed() + 7, x / 14.0, z / 14.0);
        double under = fbm(p.seed() + 13, x / 9.0, z / 9.0);

        int top = centerY + (int) Math.round(p.topHeight() * (1.0 - d * d) + 1.5 * bumps);
        // Inverted cone with a ragged underside, the classic floating island look.
        int bottom = centerY - 2 - (int) Math.round(p.depth() * Math.pow(1.0 - d, 1.7) * (0.8 + 0.25 * under));
        if (top <= bottom) {
            top = bottom + 1;
        }
        out[0] = top;
        out[1] = bottom;
        return true;
    }
}
