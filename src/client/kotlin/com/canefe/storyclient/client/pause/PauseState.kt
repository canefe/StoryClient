package com.canefe.storyclient.client.pause

/**
 * Client-side mirror of story-sim's run/pause state, pushed from the server over
 * the `story:pause_state` plugin channel. While [paused] is true the player is
 * frozen in place (see PlayerFreezeMixin) — a paused world should not let the
 * player keep walking around in it.
 *
 * Read from the render/input thread (mixin) and written from the netty thread
 * (payload receiver), so kept @Volatile.
 */
object PauseState {
    @Volatile
    var paused: Boolean = false
}
