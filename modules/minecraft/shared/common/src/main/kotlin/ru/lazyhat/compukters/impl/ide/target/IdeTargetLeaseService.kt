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

import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID

fun interface IdeTargetClaimResolver {
    fun resolve(
        player: UUID,
        claim: IdeTargetClaim,
    ): IdeClaimResolution
}

sealed interface IdeClaimResolution {
    data class Resolved(
        val target: IdeResolvedTarget,
    ) : IdeClaimResolution

    data class Rejected(
        val failure: IdeTargetFailure,
    ) : IdeClaimResolution
}

data class IdeResolvedTarget(
    val machineIdentity: String,
    val profileId: IdeTargetProfileId,
    val profile: TargetCompileProfile,
    val capabilities: IdeTargetCapabilities,
    val displayName: String,
    val alive: () -> Boolean,
    val deployment: IdeTargetDeploymentOperations,
    val terminal: IdeTargetTerminalOperations? = null,
    val fileSystem: IdeTargetFileSystemOperations? = null,
) {
    init {
        require(machineIdentity.isNotBlank()) { "machine identity must not be blank" }
    }
}

class IdeTargetTerminalOperations(
    val machineId: () -> Long?,
    val fullState: suspend () -> TerminalState?,
    val changesSince: suspend (Long) -> TerminalUpdate?,
    val submitKey: suspend (TerminalKey, TerminalKeyAction, Set<TerminalModifier>) -> Boolean,
    val submitText: suspend (String) -> Boolean,
)

class IdeTargetDeploymentOperations(
    val verifyForDeploy: suspend (ByteArray) -> ProgramDeploymentCandidate?,
    val executableRevision: suspend (String) -> ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision? = { null },
    val deploy: suspend (String, ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision, ProgramDeploymentCandidate) ->
    ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision? = { _, _, _ -> null },
    val submitCanonicalLine: suspend (CharArray) -> Boolean = { false },
)

class IdeTargetFileSystemOperations(
    val stat: suspend (ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath) -> ru.lazyhat.compukters.lang.runtime.fs.VmFileStat?,
    val list: suspend (ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath, String?, Int) ->
    ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing?,
    val read: suspend (ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath, Long, Int, Long) ->
    ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk?,
)

class IdeTargetLeaseService(
    private val resolver: IdeTargetClaimResolver,
    private val targetIds: () -> IdeTargetId = { IdeTargetId(UUID.randomUUID().toString()) },
    private val leaseTicks: Long = DEFAULT_LEASE_TICKS,
) : AutoCloseable {
    private val leases = mutableMapOf<UUID, Lease>()
    private val removalListeners = mutableSetOf<(UUID, IdeAttachedTarget) -> Unit>()
    private var closed = false

    init {
        require(leaseTicks > 0) { "target lease duration must be positive" }
    }

    fun attach(
        player: UUID,
        claim: IdeTargetClaim,
        tick: Long,
    ): IdeAttachResult {
        checkOpen()
        require(tick >= 0) { "server tick must not be negative" }
        val resolved =
            when (val resolution = resolver.resolve(player, claim)) {
                is IdeClaimResolution.Rejected -> return IdeAttachResult.Rejected(resolution.failure)
                is IdeClaimResolution.Resolved -> resolution.target
            }
        if (!resolved.alive()) return IdeAttachResult.Rejected(targetLost())
        val attached =
            IdeAttachedTarget(
                id = targetIds(),
                profile = resolved.profileId,
                compileProfile = resolved.profile,
                capabilities = resolved.capabilities,
                displayName = resolved.displayName,
            )
        removeLease(player)
        leases[player] = Lease(attached, resolved, expiresAt(tick))
        return IdeAttachResult.Attached(attached)
    }

    fun heartbeat(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): IdeHeartbeatResult {
        checkOpen()
        val lease = liveLease(player, target, tick) ?: return IdeHeartbeatResult.Lost(targetLost())
        leases[player] = lease.copy(expiresAt = expiresAt(tick))
        return IdeHeartbeatResult.Alive
    }

    fun access(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): IdeResolvedTarget? {
        checkOpen()
        return liveLease(player, target, tick)?.resolved
    }

    fun attached(
        player: UUID,
        reference: IdeTargetReference,
        tick: Long,
    ): IdeAttachedTarget? {
        checkOpen()
        val lease = liveLease(player, reference, tick) ?: return null
        return lease.target
    }

    fun detach(
        player: UUID,
        target: IdeAttachedTarget,
    ) {
        checkOpen()
        if (leases[player]?.target == target) removeLease(player)
    }

    fun observeRemovals(listener: (UUID, IdeAttachedTarget) -> Unit): AutoCloseable {
        checkOpen()
        removalListeners += listener
        return AutoCloseable { removalListeners -= listener }
    }

    fun expire(tick: Long) {
        checkOpen()
        require(tick >= 0) { "server tick must not be negative" }
        leases.entries
            .filter { (_, lease) -> tick >= lease.expiresAt || !lease.resolved.alive() }
            .map { it.key }
            .forEach(::removeLease)
    }

    override fun close() {
        if (closed) return
        leases.keys.toList().forEach(::removeLease)
        closed = true
        removalListeners.clear()
    }

    private fun liveLease(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
    ): Lease? {
        require(tick >= 0) { "server tick must not be negative" }
        val lease = leases[player] ?: return null
        if (lease.target != target) return null
        if (tick >= lease.expiresAt || !lease.resolved.alive()) {
            removeLease(player)
            return null
        }
        return lease
    }

    private fun liveLease(
        player: UUID,
        reference: IdeTargetReference,
        tick: Long,
    ): Lease? {
        require(tick >= 0) { "server tick must not be negative" }
        val lease = leases[player] ?: return null
        if (lease.target.id != reference.id || lease.target.profile != reference.profile) return null
        if (tick >= lease.expiresAt || !lease.resolved.alive()) {
            removeLease(player)
            return null
        }
        return lease
    }

    private fun expiresAt(tick: Long): Long =
        try {
            Math.addExact(tick, leaseTicks)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun checkOpen() = check(!closed) { "target lease service is closed" }

    private fun removeLease(player: UUID) {
        val removed = leases.remove(player) ?: return
        removalListeners.toList().forEach { listener -> listener(player, removed.target) }
    }

    private data class Lease(
        val target: IdeAttachedTarget,
        val resolved: IdeResolvedTarget,
        val expiresAt: Long,
    )

    private companion object {
        const val DEFAULT_LEASE_TICKS = 300L

        fun targetLost() = IdeTargetFailure(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
    }
}
