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

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.network.ServerOperationScope
import ru.lazyhat.compukters.impl.terminal.TerminalInputAdmission
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID

internal data class IdeTerminalDelivery(
    val player: UUID,
    val payload: CustomPacketPayload,
)

internal class IdeTargetTerminalSessionService(
    private val leases: IdeTargetLeaseService,
    private val tokens: () -> UUID = UUID::randomUUID,
    private val inputAdmission: (UUID, Long) -> Boolean,
) : AutoCloseable {
    private val sessionsByPlayer = mutableMapOf<UUID, Session>()
    private val playersByToken = mutableMapOf<UUID, UUID>()
    private val pendingDeliveries = mutableListOf<IdeTerminalDelivery>()
    private val removalObservation = leases.observeRemovals(::targetRemoved)
    private var closed = false
    private val polls = ServerOperationScope(maximumPendingPerPlayer = 1)
    private val pendingOpens = mutableMapOf<UUID, Any>()

    constructor(
        leases: IdeTargetLeaseService,
        tokens: () -> UUID = UUID::randomUUID,
    ) : this(leases, tokens, TerminalInputAdmission::accept)

    suspend fun open(
        player: UUID,
        generation: Long,
        target: IdeTargetReference,
        tick: Long,
    ): CustomPacketPayload {
        checkOpen()
        val attached =
            leases.attached(player, target, tick)
                ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable", true)
        val resolved =
            leases.access(player, attached, tick)
                ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable", true)
        val terminal = resolved.terminal
        if (!resolved.capabilities.terminal || terminal == null) {
            return failure(generation, null, IdeTargetFailureKind.Unsupported, "Target does not provide a terminal", false)
        }
        val machineId =
            terminal.machineId()
                ?: return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target terminal is unavailable", true)
        val opening = Any()
        pendingOpens[player] = opening
        val state =
            try {
                terminal.fullState()
            } catch (error: Throwable) {
                pendingOpens.remove(player, opening)
                throw error
            }
        if (!pendingOpens.remove(player, opening) || closed ||
            leases.access(player, attached, tick) !== resolved || terminal.machineId() != machineId || state == null
        ) {
            return failure(generation, null, IdeTargetFailureKind.TargetLost, "Target terminal is unavailable", true)
        }
        remove(player)
        val token = tokens()
        require(token.mostSignificantBits != 0L || token.leastSignificantBits != 0L) { "terminal session token must not be zero" }
        require(token !in playersByToken) { "terminal session token must be unique" }
        sessionsByPlayer[player] = Session(generation, token, attached, resolved, terminal, machineId, state.revision)
        playersByToken[token] = player
        return IdeTerminalOpenedPayload(generation, token, machineId, state)
    }

    fun publish(tick: Long): List<IdeTerminalDelivery> {
        checkOpen()
        sessionsByPlayer.keys.toList().forEach { player ->
            val session = sessionsByPlayer[player] ?: return@forEach
            if (!isLive(player, session, tick) || session.terminal.machineId() != session.machineId) {
                pendingDeliveries += IdeTerminalDelivery(player, lost(session))
                remove(player)
                return@forEach
            }
            val pending = polls.submit(player) { poll(player, session, tick) } ?: return@forEach
            // Move admitted sessions to the end so capacity pressure also serves later viewers.
            if (sessionsByPlayer[player] === session) {
                sessionsByPlayer.remove(player)
                sessionsByPlayer[player] = session
            }
            pending.whenComplete { delivery, failure ->
                if (!closed && sessionsByPlayer[player] === session) {
                    if (failure != null) {
                        pendingDeliveries += IdeTerminalDelivery(player, lost(session))
                        remove(player)
                    } else if (delivery != null) {
                        pendingDeliveries += IdeTerminalDelivery(player, delivery)
                    }
                }
            }
        }
        return pendingDeliveries.toList().also { pendingDeliveries.clear() }
    }

    private suspend fun poll(
        player: UUID,
        session: Session,
        tick: Long,
    ): CustomPacketPayload? {
        val update = session.terminal.changesSince(session.revision)
        if (!isCurrent(player, session, tick)) return null
        return when (update) {
            is TerminalUpdate.Delta -> {
                if (update.targetRevision <= session.revision) return null
                if (update.baseRevision != session.revision) {
                    snapshot(player, session, tick)
                } else {
                    session.revision = update.targetRevision
                    IdeTerminalDeltaPayload(session.token, session.machineId, update)
                }
            }

            is TerminalUpdate.Full -> {
                if (update.state.revision < session.revision) return null
                session.revision = update.state.revision
                IdeTerminalFullPayload(session.token, session.machineId, update.state)
            }

            is TerminalUpdate.Unchanged,
            null,
            -> {
                null
            }
        }
    }

    suspend fun resync(
        player: UUID,
        payload: IdeTerminalResyncPayload,
        tick: Long,
    ): CustomPacketPayload? {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return null
        val update = session.terminal.changesSince(payload.revision)
        if (!isCurrent(player, session, tick)) return null
        return when (update) {
            is TerminalUpdate.Delta -> {
                if (update.targetRevision < session.revision) return snapshot(player, session, tick)
                IdeTerminalDeltaPayload(session.token, session.machineId, update).also {
                    session.revision = maxOf(session.revision, update.targetRevision)
                }
            }

            is TerminalUpdate.Full -> {
                if (update.state.revision < session.revision) return snapshot(player, session, tick)
                IdeTerminalFullPayload(session.token, session.machineId, update.state).also {
                    session.revision = maxOf(session.revision, update.state.revision)
                }
            }

            is TerminalUpdate.Unchanged -> {
                null
            }

            null -> {
                snapshot(player, session, tick)
            }
        }
    }

    suspend fun key(
        player: UUID,
        payload: IdeTerminalKeyPayload,
        tick: Long,
    ): Boolean {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return false
        if (!inputAdmission(player, tick)) return false
        return session.terminal.submitKey(payload.key, payload.action, payload.modifiers)
    }

    suspend fun text(
        player: UUID,
        payload: IdeTerminalTextPayload,
        tick: Long,
    ): Boolean {
        checkOpen()
        val session = matching(player, payload.token, payload.machineId, tick) ?: return false
        if (!inputAdmission(player, tick)) return false
        return session.terminal.submitText(payload.text)
    }

    fun close(
        player: UUID,
        payload: IdeTerminalClosePayload,
    ): Boolean {
        checkOpen()
        if (playersByToken[payload.token] != player) return false
        remove(player)
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        polls.close()
        pendingOpens.clear()
        removalObservation.close()
        sessionsByPlayer.clear()
        playersByToken.clear()
        pendingDeliveries.clear()
    }

    private fun matching(
        player: UUID,
        token: UUID,
        machineId: Long,
        tick: Long,
    ): Session? {
        if (playersByToken[token] != player) return null
        val session = sessionsByPlayer[player] ?: return null
        if (session.token != token || session.machineId != machineId) return null
        if (!isLive(player, session, tick) || session.terminal.machineId() != machineId) {
            remove(player)
            return null
        }
        return session
    }

    private fun isLive(
        player: UUID,
        session: Session,
        tick: Long,
    ): Boolean = leases.access(player, session.attached, tick) === session.resolved

    private suspend fun full(session: Session): IdeTerminalFullPayload? =
        session.terminal.fullState()?.let { IdeTerminalFullPayload(session.token, session.machineId, it) }

    private fun isCurrent(
        player: UUID,
        session: Session,
        tick: Long,
    ): Boolean =
        !closed && sessionsByPlayer[player] === session && isLive(player, session, tick) &&
            session.terminal.machineId() == session.machineId

    private suspend fun snapshot(
        player: UUID,
        session: Session,
        tick: Long,
    ): IdeTerminalFullPayload? =
        full(session)?.takeIf { isCurrent(player, session, tick) && it.state.revision >= session.revision }?.also {
            session.revision = it.state.revision
        }

    private fun targetRemoved(
        player: UUID,
        attached: IdeAttachedTarget,
    ) {
        pendingOpens.remove(player)
        val session = sessionsByPlayer[player] ?: return
        if (session.attached != attached) return
        pendingDeliveries += IdeTerminalDelivery(player, lost(session))
        remove(player)
    }

    private fun remove(player: UUID) {
        val session = sessionsByPlayer.remove(player) ?: return
        playersByToken.remove(session.token)
    }

    private fun lost(session: Session) =
        failure(session.generation, session.token, IdeTargetFailureKind.TargetLost, "Target terminal session ended", true)

    private fun failure(
        generation: Long,
        token: UUID?,
        kind: IdeTargetFailureKind,
        detail: String,
        retryable: Boolean,
    ) = IdeTerminalFailedPayload(generation, token, kind, detail, retryable)

    private fun checkOpen() = check(!closed) { "target terminal session service is closed" }

    private data class Session(
        val generation: Long,
        val token: UUID,
        val attached: IdeAttachedTarget,
        val resolved: IdeResolvedTarget,
        val terminal: IdeTargetTerminalOperations,
        val machineId: Long,
        var revision: Long,
    )
}
