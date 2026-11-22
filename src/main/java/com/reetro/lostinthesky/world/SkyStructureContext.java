package com.reetro.lostinthesky.world;

import org.jetbrains.annotations.Nullable;

/**
 * Set while a {@link SkyJigsawStructure} lays out its pieces, so
 * {@link com.reetro.lostinthesky.mixin.JigsawPlacerMixin} can put terrain-following pieces (village
 * streets and the houses on them) on the island's surface instead of the ground far below.
 */
public final class SkyStructureContext {
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();

    /**
     * @param layer      the layer whose islands the structure sits on
     * @param fallbackY  "ground" height to use where no island is under a piece (keeps it level with the start)
     */
    public record Active(SkyWorld sky, SkyWorld.CompiledLayer layer, int fallbackY) {
        /** First free Y on the island at x/z (like a heightmap), or the fallback if there's no island there. */
        public int surfaceAt(int x, int z) {
            Integer top = sky.surfaceAt(layer, x, z);
            return top == null ? fallbackY : top + 1;
        }
    }

    private SkyStructureContext() {
    }

    @Nullable
    public static Active get() {
        return ACTIVE.get();
    }

    public static void run(Active context, Runnable action) {
        Active previous = ACTIVE.get();
        ACTIVE.set(context);
        try {
            action.run();
        } finally {
            ACTIVE.set(previous);
        }
    }
}
