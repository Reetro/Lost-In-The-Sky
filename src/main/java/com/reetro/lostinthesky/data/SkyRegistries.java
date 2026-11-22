package com.reetro.lostinthesky.data;

import com.reetro.lostinthesky.LostInTheSky;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Datapack registries. Because they are datapack-driven, other mods and modpacks can add layers,
 * biomes and encounters with plain JSON, without touching this mod's code.
 *
 * <p>Files go in {@code data/<namespace>/lost_in_the_sky/<registry>/<name>.json}.
 */
public final class SkyRegistries {
    public static final ResourceKey<Registry<SkyLayer>> SKY_LAYER = key("sky_layer");
    public static final ResourceKey<Registry<SkyBiome>> SKY_BIOME = key("sky_biome");
    public static final ResourceKey<Registry<SkyEncounter>> SKY_ENCOUNTER = key("sky_encounter");

    private SkyRegistries() {
    }

    private static <T> ResourceKey<Registry<T>> key(String name) {
        return ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(LostInTheSky.MODID, name));
    }

    /** Server-side only: clients never need these, the generated blocks are synced normally. */
    public static void onNewRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(SKY_LAYER, SkyLayer.CODEC);
        event.dataPackRegistry(SKY_BIOME, SkyBiome.CODEC);
        event.dataPackRegistry(SKY_ENCOUNTER, SkyEncounter.CODEC);
    }
}
