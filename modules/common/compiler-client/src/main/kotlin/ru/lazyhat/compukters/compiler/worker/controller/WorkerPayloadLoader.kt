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

package ru.lazyhat.compukters.compiler.worker.controller

import java.nio.file.Path

typealias WorkerPayloadLoadLimits = ru.lazyhat.compukters.worker.payload.WorkerPayloadLoadLimits

object WorkerPayloadLoader {
    fun load(
        root: Path,
        limits: WorkerPayloadLoadLimits = WorkerPayloadLoadLimits(),
    ): PublishedWorkerPayload {
        val loaded =
            ru.lazyhat.compukters.worker.payload.WorkerPayloadLoader
                .load(root, limits)
        return PublishedWorkerPayload(loaded.root, WorkerPayloadManifest.fromGeneric(loaded.manifest), loaded.classpath)
    }

    fun loadToolingProfile(
        root: Path,
        limits: ru.lazyhat.compukters.worker.payload.ToolingBundleLoadLimits =
            ru.lazyhat.compukters.worker.payload
                .ToolingBundleLoadLimits(),
    ): PublishedWorkerPayload {
        val bundle =
            ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
                .load(root, limits)
        val compiler = bundle.profile("compiler")
        return PublishedWorkerPayload(
            compiler.root,
            WorkerPayloadManifest.fromToolingProfile(compiler.manifest, bundle.manifest.files),
            compiler.classpath,
        )
    }
}
