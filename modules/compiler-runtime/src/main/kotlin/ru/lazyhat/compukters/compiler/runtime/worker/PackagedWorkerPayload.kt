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

package ru.lazyhat.compukters.compiler.runtime.worker

import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import java.io.InputStream
import java.nio.file.Path

data class PackagedWorkerPayloadLimits(
    val entries: Int = 256,
    val bytes: Long = 512L * 1024 * 1024,
    val manifestBytes: Int = 1024 * 1024,
) {
    init {
        require(entries > 0) { "packaged worker entry limit must be positive" }
        require(bytes > 0) { "packaged worker byte limit must be positive" }
        require(manifestBytes > 0) { "packaged worker manifest byte limit must be positive" }
    }
}

class PackagedWorkerPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object PackagedWorkerPayload {
    fun publish(
        bundleManifest: InputStream,
        archive: InputStream,
        cacheRoot: Path,
        limits: PackagedWorkerPayloadLimits = PackagedWorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        try {
            val bundle =
                ru.lazyhat.compukters.worker.payload.PackagedToolingBundle.publish(
                    bundleManifest,
                    archive,
                    cacheRoot,
                    limits =
                        ru.lazyhat.compukters.worker.payload.PackagedToolingBundleLimits(
                            limits.entries,
                            limits.bytes,
                            limits.manifestBytes,
                        ),
                )
            val compiler = bundle.profile(COMPILER_PROFILE)
            return PublishedWorkerPayload(
                compiler.root,
                WorkerPayloadManifest.fromToolingProfile(compiler.manifest, bundle.manifest.files),
                compiler.classpath,
            )
        } catch (exception: ru.lazyhat.compukters.worker.payload.PackagedToolingBundleException) {
            throw PackagedWorkerPayloadException(exception.message ?: "packaged worker publication failed", exception)
        } catch (exception: ru.lazyhat.compukters.worker.payload.ToolingBundleException) {
            throw PackagedWorkerPayloadException(exception.message ?: "packaged worker profile is invalid", exception)
        }
    }

    private const val COMPILER_PROFILE = "compiler"
}
