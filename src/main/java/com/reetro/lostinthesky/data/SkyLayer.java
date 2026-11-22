package com.reetro.lostinthesky.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.reetro.lostinthesky.world.IslandMath;
import net.minecraft.util.StringRepresentable;

/**
 * One band of floating islands. Loaded from
 * {@code data/<namespace>/lost_in_the_sky/sky_layer/<name>.json}.
 *
 * @param anchor     what {@code offset} is measured from: the terrain under each island, or sea level
 * @param offset     blocks above the anchor
 * @param maxY       islands whose centre would land above this are skipped (keeps the surface-anchored
 *                   layer below the next layer on tall mountains)
 * @param yJitter    random +/- variation of each island's height
 * @param cellSize   one island at most per cellSize x cellSize area; bigger = more spread out
 * @param chance     chance that a cell holds an island (0-1)
 * @param minRadius  smallest island radius
 * @param maxRadius  largest island radius (clamped to fit the cell)
 * @param topHeight  height of the domed top above the island's centre
 * @param depth      how far the rocky underside hangs below the island's centre
 * @param caveChance chance that an island has caves winding through it (0-1)
 */
public record SkyLayer(Anchor anchor, int offset, int maxY, int yJitter, int cellSize, float chance,
                       int minRadius, int maxRadius, int topHeight, int depth, float caveChance) {

    public static final Codec<SkyLayer> CODEC = RecordCodecBuilder.create(i -> i.group(
            Anchor.CODEC.optionalFieldOf("anchor", Anchor.SEA_LEVEL).forGetter(SkyLayer::anchor),
            Codec.INT.fieldOf("offset").forGetter(SkyLayer::offset),
            Codec.INT.optionalFieldOf("max_y", 1990).forGetter(SkyLayer::maxY),
            Codec.intRange(0, 256).optionalFieldOf("y_jitter", 16).forGetter(SkyLayer::yJitter),
            Codec.intRange(32, 1024).optionalFieldOf("cell_size", 160).forGetter(SkyLayer::cellSize),
            Codec.floatRange(0f, 1f).optionalFieldOf("chance", 0.5f).forGetter(SkyLayer::chance),
            Codec.intRange(4, 512).optionalFieldOf("min_radius", 24).forGetter(SkyLayer::minRadius),
            Codec.intRange(4, 512).optionalFieldOf("max_radius", 64).forGetter(SkyLayer::maxRadius),
            Codec.intRange(1, 128).optionalFieldOf("top_height", 8).forGetter(SkyLayer::topHeight),
            Codec.intRange(4, 256).optionalFieldOf("depth", 40).forGetter(SkyLayer::depth),
            Codec.floatRange(0f, 1f).optionalFieldOf("cave_chance", 0.4f).forGetter(SkyLayer::caveChance)
    ).apply(i, SkyLayer::new));

    public IslandMath.LayerShape shape() {
        return new IslandMath.LayerShape(cellSize, chance, minRadius, maxRadius, topHeight, depth, yJitter);
    }

    public enum Anchor implements StringRepresentable {
        /** Offset is measured from the terrain height under the island's centre. */
        SURFACE("surface"),
        /** Offset is measured from the world's sea level. */
        SEA_LEVEL("sea_level");

        public static final Codec<Anchor> CODEC = StringRepresentable.fromEnum(Anchor::values);

        private final String name;

        Anchor(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
