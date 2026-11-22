package com.reetro.lostinthesky.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * An ore that generates in the rocky underside of a layer's islands. Loaded from
 * {@code data/<namespace>/lost_in_the_sky/sky_ore/<name>.json}. The default files make higher
 * layers richer: coal and copper on the lower islands up to diamonds and emeralds on the final ones.
 * Ores from mods that aren't installed are skipped.
 *
 * @param layer     id of the sky_layer whose islands get this ore
 * @param block     the ore block
 * @param chance    roughly the fraction of underside stone that becomes this ore (0.01 = 1%)
 * @param veinSize  average blocks per vein, 1-27 (veins fit in a 3x3x3 box)
 */
public record SkyOre(ResourceLocation layer, ResourceLocation block, float chance, int veinSize) {

    public static final Codec<SkyOre> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("layer").forGetter(SkyOre::layer),
            ResourceLocation.CODEC.fieldOf("block").forGetter(SkyOre::block),
            Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(SkyOre::chance),
            Codec.intRange(1, 27).optionalFieldOf("vein_size", 6).forGetter(SkyOre::veinSize)
    ).apply(i, SkyOre::new));
}
