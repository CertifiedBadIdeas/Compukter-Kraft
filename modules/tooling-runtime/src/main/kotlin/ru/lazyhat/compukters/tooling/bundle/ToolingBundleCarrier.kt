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

package ru.lazyhat.compukters.tooling.bundle

import com.github.luben.zstd.ZstdOutputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

object ToolingBundleCarrier {
    fun encode(
        archive: Path,
        output: Path,
    ) {
        output.parent.createDirectories()
        archive.inputStream().buffered().use { input ->
            ZstdOutputStream(output.outputStream().buffered(), ZSTD_COMPRESSION_LEVEL)
                .setChecksum(true)
                .use(input::transferTo)
        }
    }

    private const val ZSTD_COMPRESSION_LEVEL = 19
}
