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
import ru.lazyhat.compukters.impl.terminal.TerminalReplica
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import java.util.UUID

internal fun interface IdeTargetTerminalTransport {
    fun send(payload: CustomPacketPayload)
}

internal sealed interface IdeTargetTerminalState {
    data object Closed : IdeTargetTerminalState

    data class Opening(
        val generation: Long,
    ) : IdeTargetTerminalState

    data class Active(
        val token: UUID,
        val machineId: Long,
        val replica: TerminalReplica,
    ) : IdeTargetTerminalState

    data class Resyncing(
        val token: UUID,
        val machineId: Long,
        val replica: TerminalReplica,
    ) : IdeTargetTerminalState

    data class Failed(
        val detail: String,
        val retryable: Boolean,
    ) : IdeTargetTerminalState
}

internal class IdeTargetTerminalClient(
    private val transport: IdeTargetTerminalTransport,
) : AutoCloseable {
    private var target: IdeTargetReference? = null
    private var visible = false
    private var generation = 0L
    private var current: IdeTargetTerminalState = IdeTargetTerminalState.Closed
    private var closed = false

    fun state(): IdeTargetTerminalState = current

    fun setTarget(target: IdeTargetReference?) {
        checkOpen()
        if (this.target == target) return
        releaseRemote()
        this.target = target
        current = IdeTargetTerminalState.Closed
        if (visible && target != null) beginOpen(target)
    }

    fun setVisible(visible: Boolean) {
        checkOpen()
        this.visible = visible
        if (visible && target != null && (current is IdeTargetTerminalState.Closed || current is IdeTargetTerminalState.Failed)) {
            beginOpen(checkNotNull(target))
        }
    }

    fun accept(payload: IdeTerminalOpenedPayload) {
        val opening = current as? IdeTargetTerminalState.Opening ?: return
        if (payload.generation != opening.generation) return
        current = IdeTargetTerminalState.Active(payload.token, payload.machineId, TerminalReplica(payload.state))
    }

    fun accept(payload: IdeTerminalFullPayload) {
        val session = session() ?: return
        if (payload.token != session.token || payload.machineId != session.machineId) return
        if (!session.replica.replace(payload.state)) return
        current = IdeTargetTerminalState.Active(session.token, session.machineId, session.replica)
    }

    fun accept(payload: IdeTerminalDeltaPayload) {
        val active = current as? IdeTargetTerminalState.Active ?: return
        if (payload.token != active.token || payload.machineId != active.machineId) return
        if (active.replica.apply(payload.delta)) return
        current = IdeTargetTerminalState.Resyncing(active.token, active.machineId, active.replica)
        transport.send(IdeTerminalResyncPayload(active.token, active.machineId, active.replica.state.revision))
    }

    fun accept(payload: IdeTerminalFailedPayload) {
        when (val state = current) {
            is IdeTargetTerminalState.Opening -> {
                if (payload.generation != state.generation || payload.token != null) return
            }

            is IdeTargetTerminalState.Active -> {
                if (payload.token != state.token) return
            }

            is IdeTargetTerminalState.Resyncing -> {
                if (payload.token != state.token) return
            }

            is IdeTargetTerminalState.Closed,
            is IdeTargetTerminalState.Failed,
            -> {
                return
            }
        }
        current = IdeTargetTerminalState.Failed(payload.detail, payload.retryable)
    }

    fun sendKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean {
        val session = session() ?: return false
        transport.send(IdeTerminalKeyPayload(session.token, session.machineId, key, action, modifiers))
        return true
    }

    fun sendText(text: String): Boolean {
        val session = session() ?: return false
        transport.send(IdeTerminalTextPayload(session.token, session.machineId, text))
        return true
    }

    fun connectionLost() {
        if (closed) return
        current = IdeTargetTerminalState.Closed
    }

    override fun close() {
        if (closed) return
        releaseRemote()
        current = IdeTargetTerminalState.Closed
        closed = true
    }

    private fun beginOpen(target: IdeTargetReference) {
        generation = Math.incrementExact(generation)
        current = IdeTargetTerminalState.Opening(generation)
        transport.send(IdeTerminalOpenPayload(generation, target))
    }

    private fun releaseRemote() {
        session()?.let { transport.send(IdeTerminalClosePayload(it.token)) }
    }

    private fun session(): SessionView? =
        when (val state = current) {
            is IdeTargetTerminalState.Active -> SessionView(state.token, state.machineId, state.replica)

            is IdeTargetTerminalState.Resyncing -> SessionView(state.token, state.machineId, state.replica)

            is IdeTargetTerminalState.Closed,
            is IdeTargetTerminalState.Failed,
            is IdeTargetTerminalState.Opening,
            -> null
        }

    private fun checkOpen() = check(!closed) { "target terminal client is closed" }

    private data class SessionView(
        val token: UUID,
        val machineId: Long,
        val replica: TerminalReplica,
    )
}
