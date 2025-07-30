package com.canefe.storyclient.client

import net.kyori.adventure.text.Component
import net.minecraft.text.Text
import net.kyori.adventure.platform.fabric.FabricClientAudiences

fun convertAdventureToText(audiences: FabricClientAudiences, adventureComponent: Component): Text {
    // Use the asNative method provided by the Adventure codebase
    return audiences.toNative(adventureComponent)
}