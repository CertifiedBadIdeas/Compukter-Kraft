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

package ru.lazyhat.compukters.ide.analysis.k2.standalone

import com.intellij.openapi.application.ReadAction
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.psi.KtFile
import ru.lazyhat.compukters.ide.analysis.k2.testPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class K2ProjectEnvironmentIsolationTest {
    @Test
    fun `worker runtime classes are invisible without an admitted bundle`() {
        val root = createTempDirectory("compukters-k2-isolation-")
        val source = root.resolve("source")
        Files.createDirectories(source)
        Files.writeString(
            source.resolve("main.kt"),
            """
            import ru.lazyhat.compukters.ide.analysis.AnalysisQuery

            val leaked: AnalysisQuery? = null
            """.trimIndent(),
        )
        val environment =
            K2ProjectEnvironment.create(
                source,
                testPlatform(),
                emptySet(),
            )
        try {
            val diagnostics =
                ReadAction.compute<Set<String>, RuntimeException> {
                    val file =
                        environment.session.modulesWithFiles.values
                            .flatten()
                            .single() as KtFile
                    analyze(file) {
                        file
                            .collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                            .mapTo(mutableSetOf()) { it.factoryName }
                    }
                }

            assertTrue("UNRESOLVED_REFERENCE" in diagnostics, diagnostics.toString())
        } finally {
            environment.close()
            root.toFile().deleteRecursively()
        }
    }
}
