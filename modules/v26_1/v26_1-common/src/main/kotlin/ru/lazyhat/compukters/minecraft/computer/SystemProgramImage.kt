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

package ru.lazyhat.compukters.minecraft.computer

internal object SystemProgramImage {
    fun boot(): ByteArray = load(BOOT_RESOURCE, "boot")

    fun shell(): ByteArray = load(SHELL_RESOURCE, "shell")

    fun kotlinc(): ByteArray = load(KOTLINC_RESOURCE, "kotlinc")

    fun edit(): ByteArray = load(EDIT_RESOURCE, "edit")

    fun vmbench(): ByteArray = load(VMBENCH_RESOURCE, "vmbench")

    private fun load(
        resource: String,
        name: String,
    ): ByteArray =
        checkNotNull(SystemProgramImage::class.java.getResourceAsStream(resource)) {
            "packaged system $name is missing: $resource"
        }.use { it.readAllBytes() }

    private const val BOOT_RESOURCE = "/system/programs/boot"
    private const val SHELL_RESOURCE = "/system/programs/shell"
    private const val KOTLINC_RESOURCE = "/system/programs/kotlinc"
    private const val EDIT_RESOURCE = "/system/programs/edit"
    private const val VMBENCH_RESOURCE = "/system/programs/vmbench"
}
