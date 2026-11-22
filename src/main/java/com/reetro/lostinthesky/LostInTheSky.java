package com.reetro.lostinthesky;

import com.mojang.logging.LogUtils;
import com.reetro.lostinthesky.data.SkyRegistries;
import com.reetro.lostinthesky.world.SkyIslandFeature;
import com.reetro.lostinthesky.world.SkyJigsawStructure;
import com.reetro.lostinthesky.world.SkyWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Lost in the Sky: a 2,000-block-tall overworld with four layers of floating islands.
 *
 * <ul>
 *   <li>World height: {@code data/minecraft/dimension_type/overworld.json}</li>
 *   <li>Island blocks: {@link SkyIslandFeature}</li>
 *   <li>Island biomes: {@link com.reetro.lostinthesky.mixin.ChunkAccessMixin}</li>
 *   <li>Big dungeons on islands: {@link SkyJigsawStructure}</li>
 *   <li>Layers, biomes, bosses: JSON, see {@link SkyRegistries}</li>
 * </ul>
 */
@Mod(LostInTheSky.MODID)
public class LostInTheSky {
    public static final String MODID = "lost_in_the_sky";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, MODID);

    public static final DeferredHolder<Feature<?>, SkyIslandFeature> SKY_ISLANDS =
            FEATURES.register("sky_islands", () -> new SkyIslandFeature(NoneFeatureConfiguration.CODEC));

    public static final DeferredHolder<StructureType<?>, StructureType<SkyJigsawStructure>> SKY_JIGSAW =
            STRUCTURE_TYPES.register("sky_jigsaw", () -> () -> SkyJigsawStructure.CODEC);

    public LostInTheSky(IEventBus modEventBus, ModContainer modContainer) {
        FEATURES.register(modEventBus);
        STRUCTURE_TYPES.register(modEventBus);
        modEventBus.addListener(SkyRegistries::onNewRegistry);
        NeoForge.EVENT_BUS.addListener(LostInTheSky::onServerStopped);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SkyWorld.reset();
    }
}
