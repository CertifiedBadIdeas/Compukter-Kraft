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

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfileIdentity
import ru.lazyhat.compukters.impl.compat.compuktersIdentifier
import ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
import ru.lazyhat.compukters.impl.network.awaitServerResult
import ru.lazyhat.compukters.impl.terminal.TerminalNetwork
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.UUID

internal class NeoForgeIdeTargetResolver(
    private val server: MinecraftServer,
) : IdeTargetClaimResolver {
    override fun resolve(
        player: UUID,
        claim: IdeTargetClaim,
    ): IdeClaimResolution {
        val serverPlayer = server.playerList.getPlayer(player) ?: return rejected("Player is no longer connected")
        val origin = IdeTargetClaimCodec.decode(claim) ?: return rejected("Target claim is malformed")
        if (origin.dimension !=
            serverPlayer
                .level()
                .dimension()
                .compuktersIdentifier()
                .toString()
        ) {
            return rejected("Target is in another dimension")
        }
        val entity =
            serverPlayer.level().getBlockEntity(origin.position) as? ComputerBlockEntity
                ?: return rejected("Target computer is unavailable")
        if (!origin.isAuthorized(serverPlayer, entity)) return rejected("Target computer is not interactable")
        // Starts boot without waiting; target operations are ordered behind it in the actor mailbox.
        entity.prepareTerminalAsync()
        val machineId = entity.terminalMachineId ?: return rejected("Target VM is unavailable")
        val profile = NeoForgeCompilerServices.targetProfile(server)
        val dimension =
            serverPlayer
                .level()
                .dimension()
                .compuktersIdentifier()
                .toString()
        val position = entity.blockPos
        return IdeClaimResolution.Resolved(
            IdeResolvedTarget(
                machineIdentity = "$dimension:${position.x},${position.y},${position.z}:$machineId",
                profileId = IdeTargetProfileId(TargetCompileProfileIdentity.of(profile).hash),
                profile = profile,
                capabilities =
                    IdeTargetCapabilities(
                        writableFileSystem = true,
                        canonicalInput = true,
                        terminal = true,
                        readableFileSystem = true,
                    ),
                displayName = "Computer ${position.x}, ${position.y}, ${position.z}",
                alive = {
                    !entity.isRemoved &&
                        serverPlayer.level().getBlockEntity(position) === entity &&
                        entity.terminalMachineId == machineId
                },
                deployment =
                    IdeTargetDeploymentOperations(
                        verifyForDeploy = { entity.verifyForDeployAsync(it).awaitServerResult() },
                        executableRevision = { entity.executableRevisionAsync(it).awaitServerResult() },
                        deploy = { path, expected, candidate -> entity.deployAsync(path, expected, candidate).awaitServerResult() },
                        submitCanonicalLine = { entity.submitCanonicalLineAsync(it).awaitServerResult() },
                    ),
                terminal =
                    IdeTargetTerminalOperations(
                        machineId = { entity.terminalMachineId },
                        fullState = { entity.terminalFullStateAsync().awaitServerResult() },
                        changesSince = { entity.terminalChangesSinceAsync(it).awaitServerResult() },
                        submitKey = { key, action, modifiers -> entity.submitTerminalKeyAsync(key, action, modifiers).awaitServerResult() },
                        submitText = { entity.submitTerminalTextAsync(it).awaitServerResult() },
                    ),
                fileSystem =
                    IdeTargetFileSystemOperations(
                        stat = { entity.fileStatAsync(it).awaitServerResult() },
                        list = { path, startAfter, maximumEntries ->
                            entity.fileListAsync(path, startAfter, maximumEntries).awaitServerResult()
                        },
                        read = { path, offset, maximumBytes, generation ->
                            entity.fileReadAsync(path, offset, maximumBytes, generation).awaitServerResult()
                        },
                    ),
            ),
        )
    }

    private fun IdeTargetClaimOrigin.isAuthorized(
        player: ServerPlayer,
        entity: ComputerBlockEntity,
    ): Boolean =
        when (this) {
            is IdeTargetClaimOrigin.Terminal -> {
                entity.terminalMachineId == machineId && TerminalNetwork.isViewing(player, position, machineId)
            }

            is IdeTargetClaimOrigin.Crosshair -> {
                val hit = player.pick(player.blockInteractionRange(), 1.0f, false) as? BlockHitResult
                hit?.type == HitResult.Type.BLOCK && hit.blockPos == position
            }
        }

    private fun rejected(detail: String) = IdeClaimResolution.Rejected(IdeTargetFailure(IdeTargetFailureKind.Permission, detail))
}
