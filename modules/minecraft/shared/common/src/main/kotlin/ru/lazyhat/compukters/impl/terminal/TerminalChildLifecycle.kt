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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.terminal

class TerminalChildLifecycle(
    private val connectionIdentity: () -> Any?,
    private val connected: () -> Boolean,
    private val closeObservation: () -> Unit,
    private val requestFreshObservation: () -> Unit,
) {
    var suspended: Boolean = false
        private set
    private var capturedConnection: Any? = null

    fun suspend() {
        if (suspended) return
        suspended = true
        capturedConnection = connectionIdentity()
        closeObservation()
    }

    fun resume(): Boolean {
        if (!suspended) return false
        if (capturedConnection == null || capturedConnection !== connectionIdentity() || !connected()) {
            abandon()
            return false
        }
        requestFreshObservation()
        suspended = false
        capturedConnection = null
        return true
    }

    fun abandon() {
        if (!suspended) return
        suspended = false
        capturedConnection = null
    }

    fun sameConnection(): Boolean = suspended && capturedConnection != null && capturedConnection === connectionIdentity()
}
