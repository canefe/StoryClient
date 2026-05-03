package com.canefe.storyclient.client.puppet

/**
 * Client-side mirror of the player's server-side puppet group. Updated by
 * [PuppetGroupPayload] handler. Read by HUD overlay and the right-click
 * intercept mixin to know whether to swallow the click.
 */
object PuppetState {
    @Volatile var groupNames: List<String> = emptyList()
        private set

    val inPuppetMode: Boolean get() = groupNames.isNotEmpty()

    fun replaceAll(names: List<String>) {
        groupNames = names
    }

    /** Optimistic local update before server pushes back the new group state. */
    fun localToggle(name: String) {
        groupNames =
            if (groupNames.contains(name)) {
                groupNames - name
            } else {
                groupNames + name
            }
    }

    fun localClear() {
        groupNames = emptyList()
    }
}
