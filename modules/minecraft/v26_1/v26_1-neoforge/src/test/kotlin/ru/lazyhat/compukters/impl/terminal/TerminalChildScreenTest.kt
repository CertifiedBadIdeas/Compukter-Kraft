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

package ru.lazyhat.compukters.impl.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalChildScreenTest {
    @Test
    fun `suspended terminal closes observation and requests a fresh one before resuming`() {
        var connection: Any? = Any()
        var closes = 0
        var freshObservations = 0
        val lifecycle =
            TerminalChildLifecycle(
                { connection },
                { connection != null },
                { closes++ },
                { freshObservations++ },
            )

        lifecycle.suspend()
        assertTrue(lifecycle.suspended)
        assertTrue(lifecycle.sameConnection())
        assertEquals(1, closes)
        assertEquals(0, freshObservations)

        assertTrue(lifecycle.resume())
        assertEquals(1, freshObservations)
        assertFalse(lifecycle.suspended)
    }

    @Test
    fun `changed connection abandons suspended terminal without resync`() {
        var connection: Any? = Any()
        var closes = 0
        var freshObservations = 0
        val lifecycle =
            TerminalChildLifecycle(
                { connection },
                { true },
                { closes++ },
                { freshObservations++ },
            )
        lifecycle.suspend()
        connection = Any()

        assertFalse(lifecycle.resume())
        assertEquals(1, closes)
        assertEquals(0, freshObservations)
        assertFalse(lifecycle.suspended)
    }

    @Test
    fun `abandoned child never reopens the standalone observation`() {
        var freshObservations = 0
        val lifecycle =
            TerminalChildLifecycle(
                { CONNECTION },
                { true },
                {},
                { freshObservations++ },
            )

        lifecycle.suspend()
        lifecycle.abandon()

        assertFalse(lifecycle.suspended)
        assertEquals(0, freshObservations)
    }

    @Test
    fun `delayed standalone open cannot replace an active child screen`() {
        assertTrue(shouldOpenStandaloneTerminal(hasOpenScreen = false, requestedOpen = true))
        assertFalse(shouldOpenStandaloneTerminal(hasOpenScreen = true, requestedOpen = true))
        assertFalse(shouldOpenStandaloneTerminal(hasOpenScreen = false, requestedOpen = false))
    }

    private companion object {
        val CONNECTION = Any()
    }
}
