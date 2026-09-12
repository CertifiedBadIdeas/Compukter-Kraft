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

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZ
import org.tukaani.xz.XZOutputStream
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
            XZOutputStream(
                output.outputStream().buffered(),
                LZMA2Options(XZ_COMPRESSION_PRESET),
                XZ.CHECK_CRC64,
            ).use(input::transferTo)
        }
    }

    private const val XZ_COMPRESSION_PRESET = 8
}
