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

package ru.lazyhat.compukters.platform.k2

import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.fir.session.AbstractFirMetadataSessionFactory
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompuktersPlatformTest {
    @Test
    fun `target has exactly one stable Compukters component`() {
        assertEquals(1, CompuktersPlatforms.default.size)
        assertSame(CompuktersSimplePlatform, CompuktersPlatforms.default.single())
        assertEquals("Compukters", CompuktersSimplePlatform.platformName)
        assertEquals("Compukters", CompuktersSimplePlatform.oldFashionedDescription)
    }

    @Test
    fun `target is not a foreign or Common Kotlin platform`() {
        val foreign =
            buildList {
                add(JvmPlatforms.defaultJvmPlatform)
                add(JsPlatforms.defaultJsPlatform)
                add(NativePlatforms.unspecifiedNativePlatform)
                add(WasmPlatforms.wasmJs)
                add(WasmPlatforms.wasmWasi)
                add(CommonPlatforms.defaultCommonPlatform)
            }

        assertFalse(foreign.any { it == CompuktersPlatforms.default })
        assertFalse(foreign.flatMap { it.componentPlatforms }.contains(CompuktersSimplePlatform))
    }

    @Test
    fun `shared FIR session setup does not select JVM or JS context`() {
        val factory = CompuktersFirSessionFactory()
        val context =
            AbstractFirMetadataSessionFactory.Context(
                { error("JVM session context selected") },
                { error("JS session context selected") },
            )

        factory.createSharedLibrarySession(
            Name.special("<compukters-shared>"),
            LanguageVersionSettingsImpl.DEFAULT,
            emptyList(),
            context,
        )

        assertEquals(CompuktersPlatforms.default, factory.targetPlatform)
    }

    @Test
    fun `LL FIR configurator accepts only the Compukters target`() {
        assertTrue(CompuktersLLFirSessionConfigurator.supports(CompuktersPlatforms.default))
        assertFalse(CompuktersLLFirSessionConfigurator.supports(CommonPlatforms.defaultCommonPlatform))
        assertFalse(CompuktersLLFirSessionConfigurator.supports(JvmPlatforms.defaultJvmPlatform))
    }
}
