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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import java.util.concurrent.atomic.AtomicBoolean

class IdeClientTooling(
    val build: IdeBuildCoordinator,
    val analysis: IdeAnalysisCoordinator,
    private val lifetime: AutoCloseable? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        listOf<AutoCloseable>(build, analysis).plus(listOfNotNull(lifetime)).forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
