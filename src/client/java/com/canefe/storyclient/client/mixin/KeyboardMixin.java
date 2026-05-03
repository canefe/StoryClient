package com.canefe.storyclient.client.mixin;

import com.canefe.storyclient.client.squad.SquadCommandState;
import com.canefe.storyclient.client.squad.SquadListCache;
import com.canefe.storyclient.client.squad.SquadOrderPayload;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Squad command mode key handling: while in command mode,
 *   1–9         = toggle squad at that ordinal in selection
 *   0           = select all squads
 *   Esc         = exit command mode (instead of opening pause menu)
 *
 * Vanilla keypresses are swallowed for these keys while in command mode so
 * the player's hotbar selection / pause menu doesn't fight us.
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void storyclient$squadCommandKey(
        long window, int key, int scancode, int action, int modifiers, CallbackInfo ci
    ) {
        if (action != GLFW.GLFW_PRESS) return;
        if (!SquadCommandState.INSTANCE.getCommandMode()) return;

        // Only intercept when we're in-game (not on a screen).
        if (MinecraftClient.getInstance().currentScreen != null) return;

        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int ordinal = key - GLFW.GLFW_KEY_1 + 1;
            SquadListCache.Entry entry = SquadCommandState.INSTANCE.squadAtOrdinal(ordinal);
            if (entry != null) {
                SquadCommandState.INSTANCE.toggleSelection(entry.getId());
            }
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_0) {
            // 0 = select all
            SquadCommandState.INSTANCE.selectAll();
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_ESCAPE) {
            SquadCommandState.INSTANCE.exitCommandMode();
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_F2) {
            // Hold position
            SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                SquadOrderPayload.Companion.hold(squadId);
                return kotlin.Unit.INSTANCE;
            });
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_F3) {
            // Follow me
            SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                SquadOrderPayload.Companion.followSelf(squadId);
                return kotlin.Unit.INSTANCE;
            });
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_F4) {
            // Cancel current order (squad goes Idle, vanilla AI takes over)
            SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                SquadOrderPayload.Companion.idle(squadId);
                return kotlin.Unit.INSTANCE;
            });
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_F5) {
            // Cycle formation for each selected squad: LINE → WEDGE → COLUMN →
            // LOOSE → SQUARE → CIRCLE → ECHELON → LINE → ...
            // Each squad advances based on its OWN current formation, so two
            // squads with different formations stay out of sync (correct).
            int formationCount = 7;
            SquadOrderPayload.Companion.forEachSelected((squadId) -> {
                com.canefe.storyclient.client.squad.SquadListCache.Entry entry =
                    com.canefe.storyclient.client.squad.SquadListCache.INSTANCE.byId(squadId);
                int currentOrdinal = 0;
                if (entry != null) {
                    try {
                        currentOrdinal = com.canefe.storyclient.client.squad.ClientSquadFormation
                            .valueOf(entry.getFormationLabel().toUpperCase()).ordinal();
                    } catch (IllegalArgumentException ignored) {
                        // Unknown formation label — fall back to 0
                    }
                }
                int nextOrdinal = (currentOrdinal + 1) % formationCount;
                SquadOrderPayload.Companion.setFormation(squadId, nextOrdinal);
                return kotlin.Unit.INSTANCE;
            });
            ci.cancel();
        }
    }
}
