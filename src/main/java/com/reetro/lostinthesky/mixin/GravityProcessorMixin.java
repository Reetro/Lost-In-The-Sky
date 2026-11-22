package com.reetro.lostinthesky.mixin;

import com.reetro.lostinthesky.world.SkyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Terrain-following structure pieces (village streets) snap every block to the heightmap when
 * placed. For a structure on a sky island that's the ground hundreds of blocks below, because the
 * island isn't generated yet. When a block would drop far below where its piece was laid out, it's
 * snapped to the island surface under it instead (or stays at its laid-out height off the edge).
 */
@Mixin(GravityProcessor.class)
public abstract class GravityProcessorMixin {
    /** A drop bigger than this means the piece was laid out in the sky. */
    private static final int SKY_DROP = 24;
    /** How close the laid-out height must be to an island surface to snap to it. */
    private static final int SNAP_RANGE = 48;

    @Shadow
    @Final
    private int offset;

    @Inject(method = "processBlock", at = @At("RETURN"), cancellable = true)
    private void lostinthesky$snapToIsland(LevelReader level, BlockPos offsetPos, BlockPos pos,
                                           StructureTemplate.StructureBlockInfo blockInfo,
                                           StructureTemplate.StructureBlockInfo relativeBlockInfo,
                                           StructurePlaceSettings settings,
                                           CallbackInfoReturnable<StructureTemplate.StructureBlockInfo> cir) {
        StructureTemplate.StructureBlockInfo result = cir.getReturnValue();
        BlockPos planned = relativeBlockInfo.pos();
        if (result == null || planned.getY() - result.pos().getY() <= SKY_DROP
                || level.getHeight() < SkyWorld.MIN_SKY_WORLD_HEIGHT) {
            return;
        }
        SkyWorld sky = SkyWorld.get();
        if (sky == null) {
            return;
        }
        int y = planned.getY();
        int best = Integer.MAX_VALUE;
        for (SkyWorld.CompiledLayer layer : sky.layers()) {
            Integer top = sky.surfaceAt(layer, planned.getX(), planned.getZ());
            if (top != null && Math.abs(top - y) <= SNAP_RANGE && Math.abs(top - y) < Math.abs(best - y)) {
                best = top;
            }
        }
        if (best != Integer.MAX_VALUE) {
            // Same formula as vanilla: first free Y + offset + the block's height inside the template.
            y = best + 1 + offset + blockInfo.pos().getY();
        }
        cir.setReturnValue(new StructureTemplate.StructureBlockInfo(
                new BlockPos(planned.getX(), y, planned.getZ()), result.state(), result.nbt()));
    }
}
