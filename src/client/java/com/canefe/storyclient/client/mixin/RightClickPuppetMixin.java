package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.puppet.PuppetCommandPayload;
import com.canefe.storyclient.client.puppet.PuppetState;
import com.canefe.storyclient.client.squad.SquadCommandState;
import com.canefe.storyclient.client.squad.SquadOrderPayload;
import com.canefe.storyclient.client.wheel.ActionWheelHud;
import com.canefe.storyclient.client.wheel.NearbyNPCCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the player is in puppet mode (server has at least one NPC in their
 * group), right-click is repurposed: it issues a move/interact order to the
 * group instead of doing the vanilla right-click action.
 *
 * Block / air → MOVE_TO at hit position (or 50 blocks along the ray for air)
 * Living entity (NPC) → opens the puppet target wheel — handled by ActionWheelHud
 * Other entity → MOVE_TO at entity position
 *
 * Wheel-modal clicks are handled by MouseButtonMixin and take precedence; this
 * mixin only fires when no wheel is consuming the click.
 */
@Mixin(Mouse.class)
public abstract class RightClickPuppetMixin {
    private static final double MAX_RAY = 50.0;

    @Inject(method = "onMouseButton(JIII)V", at = @At("HEAD"), cancellable = true)
    private void storyclient$puppetInterceptRightClick(
        long window, int button, int action, int mods, CallbackInfo ci
    ) {
        if (action != GLFW.GLFW_PRESS) return;
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (ActionWheelHud.INSTANCE.getOpen()) return; // let the wheel mixin handle clicks

        boolean squadMode =
            SquadCommandState.INSTANCE.getCommandMode()
                && !SquadCommandState.INSTANCE.getSelectedSquadIds().isEmpty();
        boolean puppetMode = PuppetState.INSTANCE.getInPuppetMode();
        if (!squadMode && !puppetMode) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.cameraEntity == null) return;

        Entity camera = client.cameraEntity;
        Vec3d start = camera.getEyePos();
        Vec3d direction = camera.getRotationVec(1.0f);
        Vec3d end = start.add(direction.multiply(MAX_RAY));

        // Entity raycast first (entities can intercept the ray before blocks)
        Box searchBox = new Box(start, end).expand(1.0);
        Entity hitEntity = client.world.getOtherEntities(camera, searchBox, e -> e instanceof LivingEntity)
            .stream()
            .filter(e -> e.getBoundingBox().expand(0.3).raycast(start, end).isPresent())
            .min((a, b) -> Double.compare(a.squaredDistanceTo(camera), b.squaredDistanceTo(camera)))
            .orElse(null);

        String worldKey = client.world.getRegistryKey().getValue().getPath();

        if (hitEntity != null) {
            if (squadMode) {
                // If the hit entity is a known NPC (Citizens or Mythic disguise),
                // route as NPC engage even though it looks like a player client-side.
                boolean isKnownNpc = NearbyNPCCache.INSTANCE.get(hitEntity.getUuid()) != null;
                boolean isPlayer =
                    !isKnownNpc && hitEntity instanceof net.minecraft.entity.player.PlayerEntity;
                java.util.UUID targetId = hitEntity.getUuid();
                SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                    SquadOrderPayload.Companion.engage(squadId, targetId, isPlayer);
                    return kotlin.Unit.INSTANCE;
                });
                ci.cancel();
                return;
            }
            // Puppet path (unchanged)
            NearbyNPCCache.Entry npcEntry = NearbyNPCCache.INSTANCE.get(hitEntity.getUuid());
            if (npcEntry != null) {
                ActionWheelHud.INSTANCE.openPuppetTargetWheel(npcEntry);
                ci.cancel();
                return;
            }
            Vec3d ep = hitEntity.getPos();
            PuppetCommandPayload.Companion.moveTo(worldKey, ep.x, ep.y, ep.z);
            ci.cancel();
            return;
        }

        // Block / air raycast
        HitResult blockHit = client.world.raycast(
            new net.minecraft.world.RaycastContext(
                start, end,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                camera
            )
        );
        Vec3d target;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) blockHit).getBlockPos();
            target = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        } else {
            target = end;
        }

        if (squadMode) {
            // Right-click on block/air → MOVE_TO for every selected squad,
            // carrying the commander's current yaw to orient the formation.
            final Vec3d t = target;
            final float yaw = client.player.getYaw();
            SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                SquadOrderPayload.Companion.moveTo(squadId, worldKey, t.x, t.y, t.z, yaw);
                return kotlin.Unit.INSTANCE;
            });
            ci.cancel();
            return;
        }

        // Puppet path (unchanged)
        PuppetCommandPayload.Companion.moveTo(worldKey, target.x, target.y, target.z);
        ci.cancel();
    }
}
