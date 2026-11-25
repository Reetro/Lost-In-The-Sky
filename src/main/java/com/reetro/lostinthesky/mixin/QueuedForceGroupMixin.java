package com.reetro.lostinthesky.mixin;

import com.reetro.lostinthesky.ship.ThrustControl;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every Sable propeller (Aeronautics propellers and bearings, Create fans, ...) pushes its ship through
 * {@code applyAndRecordPointForce}. {@link ThrustControl} records the force and scales it by a ship brain's throttle.
 */
@Mixin(QueuedForceGroup.class)
public abstract class QueuedForceGroupMixin {
    @ModifyVariable(method = "applyAndRecordPointForce", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private Vector3dc lostinthesky$throttle(Vector3dc force, Vector3dc position) {
        return ThrustControl.onForce(position, force);
    }
}
