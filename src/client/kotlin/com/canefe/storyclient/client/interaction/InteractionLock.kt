package com.canefe.storyclient.client.interaction

import com.canefe.storyclient.client.camera.OocCameraController
import com.canefe.storyclient.client.cinematic.SpawnCinematicController
import com.canefe.storyclient.client.pause.PauseState

/**
 * Single source of truth for "the player must not interact with the world right
 * now." Movement is frozen separately by `PlayerFreezeMixin`, but freezing input
 * does not stop attacking, using/placing items, or dropping — those run through
 * their own `MinecraftClient` / `ClientPlayerEntity` paths. This predicate gates
 * all of them so the three non-interactive states behave consistently:
 *
 *  - spawn cinematic playing (camera detached, body a prop)
 *  - out-of-character spectator camera (we've stepped out of our body)
 *  - simulation paused by the server (the world is frozen)
 *
 * Consumed by the interaction mixins (attack / item-use / block-break / drop).
 */
object InteractionLock {
    val locked: Boolean
        get() =
            SpawnCinematicController.isActive ||
                OocCameraController.isActive ||
                PauseState.paused
}
