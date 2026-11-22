package com.reetro.lostinthesky.mixin;

import com.reetro.lostinthesky.world.SkyBiomeResolver;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Paints sky biomes onto islands. Every chunk generator (vanilla, Tectonic, biome mods...) fills a
 * chunk's biomes through {@link ChunkAccess#fillBiomesFromNoise}, so wrapping its resolver here
 * works no matter which generator made the ground below.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {

    @ModifyVariable(method = "fillBiomesFromNoise", at = @At("HEAD"), argsOnly = true)
    private BiomeResolver lostinthesky$wrapResolver(BiomeResolver original) {
        return SkyBiomeResolver.wrap(original, (ChunkAccess) (Object) this);
    }
}
