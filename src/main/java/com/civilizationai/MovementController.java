package com.civilizationai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Applies horizontal navigation intent as velocity.
 *
 * Position integration, gravity and collision resolution are deliberately left
 * to the normal Minecraft entity tick. This controller never teleports or calls
 * Entity.move directly.
 */
public final class MovementController {
    private static final double WALK_SPEED = 0.115;
    private static final double SPRINT_SPEED = 0.145;
    private static final double ACCELERATION = 0.32;
    private static final double STOP_THRESHOLD = 0.035;

    private MovementController() {}

    public static void moveToward(ServerPlayer player, Vec3 horizontalDirection, boolean sprint) {
        if (horizontalDirection.lengthSqr() < 1.0E-6) {
            stop(player);
            return;
        }

        Vec3 direction = horizontalDirection.normalize();
        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        Vec3 desired = direction.scale(speed);
        Vec3 current = player.getDeltaMovement();
        double blend = ACCELERATION;

        double vx = current.x + (desired.x - current.x) * blend;
        double vz = current.z + (desired.z - current.z) * blend;
        player.setDeltaMovement(vx, current.y, vz);
        player.setSprinting(sprint);
    }

    public static void stop(ServerPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        double vx = Math.abs(velocity.x) < STOP_THRESHOLD ? 0.0 : velocity.x * 0.45;
        double vz = Math.abs(velocity.z) < STOP_THRESHOLD ? 0.0 : velocity.z * 0.45;
        player.setDeltaMovement(vx, velocity.y, vz);
        player.setSprinting(false);
    }

    public static void face(ServerPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-5 && Math.abs(delta.y) < 1.0E-5) return;

        float yaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0 / Math.PI));
        float pitch = (float) (-Math.atan2(delta.y, horizontal) * (180.0 / Math.PI));
        float yawDelta = Mth.wrapDegrees(yaw - player.getYRot());

        float bodyYaw = player.getYRot() + Mth.clamp(yawDelta, -8.0F, 8.0F);
        float headYawDelta = Mth.wrapDegrees(yaw - player.getYHeadRot());
        float headYaw = player.getYHeadRot() + Mth.clamp(headYawDelta, -14.0F, 14.0F);
        float pitchDelta = pitch - player.getXRot();
        float newPitch = player.getXRot() + Mth.clamp(pitchDelta, -10.0F, 10.0F);

        player.setYRot(bodyYaw);
        player.yBodyRot = bodyYaw;
        player.setYHeadRot(headYaw);
        player.setXRot(newPitch);
    }
}
