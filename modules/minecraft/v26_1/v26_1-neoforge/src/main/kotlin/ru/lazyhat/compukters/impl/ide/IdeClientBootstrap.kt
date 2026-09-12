/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.ide

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.settings.KeyModifier
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.impl.ide.target.IdeTargetOpeningClaim
import ru.lazyhat.compukters.impl.ide.target.IdeTerminalTargetIdentity
import ru.lazyhat.compukters.impl.terminal.TerminalScreen

internal interface ChildScreenParent {
    fun suspendForChild(): Screen

    fun resumeFromChild(): Boolean

    fun abandonChild()
}

internal object IdeOpeningHandoff {
    fun <S, P> open(
        createSession: () -> S,
        attachTarget: (S) -> Unit,
        suspendParent: () -> P,
        installScreen: (S, P) -> Unit,
        closeSession: (S) -> Unit,
        resumeParent: () -> Unit,
    ) {
        val session = createSession()
        var parentSuspensionStarted = false
        try {
            attachTarget(session)
            parentSuspensionStarted = true
            val parent = suspendParent()
            installScreen(session, parent)
        } catch (failure: Throwable) {
            runCatching { closeSession(session) }.exceptionOrNull()?.let(failure::addSuppressed)
            if (parentSuspensionStarted) {
                runCatching(resumeParent).exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }
}

internal object IdeClientBootstrap {
    private val category = KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "ide"))
    internal val openIde =
        KeyMapping(
            "key.compukters.open_ide",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            category,
        )
    private var services: IdeClientServices<IdeClientApplication>? = null

    fun register(eventBus: IEventBus) {
        eventBus.addListener(::onClientSetup)
        eventBus.addListener(::onRegisterKeys)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
        NeoForge.EVENT_BUS.addListener(::onScreenKeyPressed)
    }

    fun services(): IdeClientServices<IdeClientApplication> = checkNotNull(services) { "IDE client services are not initialized" }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            if (services == null) services = productionIdeClientServices(FMLPaths.GAMEDIR.get())
        }
    }

    private fun onRegisterKeys(event: RegisterKeyMappingsEvent) {
        event.registerCategory(category)
        event.register(openIde)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val minecraft = Minecraft.getInstance()
        while (openIde.consumeClick()) open(minecraft)
    }

    private fun onScreenKeyPressed(event: ScreenEvent.KeyPressed.Pre) {
        val modifierActive = openIde.keyModifier.isActive(openIde.keyConflictContext)
        if (matchesScreenShortcut(event.keyEvent, modifierActive) && open(Minecraft.getInstance())) {
            event.isCanceled = true
        }
    }

    internal fun matchesScreenShortcut(
        event: KeyEvent,
        modifierActive: Boolean,
    ): Boolean = openIde.keyConflictContext.isActive && modifierActive && openIde.matches(event)

    internal fun open(minecraft: Minecraft): Boolean {
        if (minecraft.screen is IdeScreen) return false
        val original = minecraft.screen
        val targetClaim = targetClaim(minecraft, original)
        val childParent = original as? ChildScreenParent
        IdeOpeningHandoff.open(
            createSession = services()::open,
            attachTarget = { session -> targetClaim?.let(session.application.controller::attachTarget) },
            suspendParent = { childParent?.suspendForChild() ?: original },
            installScreen = { session, parent -> minecraft.setScreen(IdeScreen(session, parent)) },
            closeSession = IdeClientSession<IdeClientApplication>::close,
            resumeParent = { childParent?.resumeFromChild() },
        )
        return true
    }

    private fun targetClaim(
        minecraft: Minecraft,
        original: Screen?,
    ): IdeTargetClaim? {
        val terminal =
            (original as? TerminalScreen)?.let { screen ->
                IdeTerminalTargetIdentity(screen.position, screen.machineId)
            }
        val hit = minecraft.hitResult as? BlockHitResult
        val crosshair = hit?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
        val dimension =
            minecraft.level
                ?.dimension()
                ?.identifier()
                ?.toString()
        return IdeTargetOpeningClaim.create(dimension, terminal, crosshair)
    }
}
