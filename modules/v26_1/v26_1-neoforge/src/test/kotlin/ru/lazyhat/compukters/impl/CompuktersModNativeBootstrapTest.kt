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

package ru.lazyhat.compukters.impl

import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import kotlin.test.Test

class CompuktersModNativeBootstrapTest {
    @Test
    fun `mod construction loads packaged native runtime before a VM session opens`() {
        CompuktersMod.requireNativeRuntime()
        val artifact =
            checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/boot"))
                .use { it.readAllBytes() }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/shell")).use { }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/kotlinc")).use { }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/edit")).use { }
        checkNotNull(CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/system/programs/vmbench")).use { }
        checkNotNull(
            CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/tooling/workers/k2-tooling-workers.zip.xz"),
        ).use { }
        checkNotNull(
            CompuktersModNativeBootstrapTest::class.java.getResourceAsStream("/tooling/workers/k2-tooling-workers.bundle"),
        ).use { }
        check(CompuktersModNativeBootstrapTest::class.java.getResource("/compiler/worker/compiler-k2-worker.zip") == null)
        check(CompuktersModNativeBootstrapTest::class.java.getResource("/analysis/worker/ide-analysis-k2-worker.zip") == null)

        VmSession.open(artifact).use { }
    }
}
