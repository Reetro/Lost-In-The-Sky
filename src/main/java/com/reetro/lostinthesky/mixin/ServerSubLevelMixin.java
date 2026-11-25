package com.reetro.lostinthesky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.reetro.lostinthesky.ship.ThrustControl;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks which actor is running its physics tick, so {@link ThrustControl} can measure and throttle its force. */
@Mixin(ServerSubLevel.class)
public abstract class ServerSubLevelMixin {
    @WrapOperation(
            method = "prePhysicsTick",
            at = @At(value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/block/BlockEntitySubLevelActor;sable$physicsTick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V"))
    private void lostinthesky$trackActor(BlockEntitySubLevelActor actor, ServerSubLevel subLevel, RigidBodyHandle handle,
                                         double dt, Operation<Void> original) {
        ThrustControl.begin(actor);
        try {
            original.call(actor, subLevel, handle, dt);
        } finally {
            ThrustControl.end(dt, subLevel.getLevel().getGameTime());
        }
    }
}
