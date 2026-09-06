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

package ru.lazyhat.compukters.platform.source

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalPlatformSourceTest {
    private val root = Path.of(checkNotNull(System.getProperty("compukters.platform.source-root")))
    private val catalog by lazy { SourceCatalog.parse(root.resolve("modules.toml").readText()) }

    @Test
    fun `builtins publish specialized IntArray contract`() {
        val arrays = root.resolve("builtins/kotlin/Arrays.kt").readText()

        assertTrue("public class IntArray external constructor(size: Int)" in arrays)
        assertTrue("public external fun intArrayOf(vararg elements: Int): IntArray" in arrays)
        assertEquals("1.1.0", catalog.modules.single { it.id == "kotlin:builtins" }.version)
    }

    @Test
    fun `every canonical source has exactly one owner`() {
        val sources =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .map { root.relativize(it).invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }

        assertTrue(sources.isNotEmpty(), "canonical platform source tree is empty")
        sources.forEach { source ->
            val owners = catalog.modules.filter { module -> module.sources.any { glob -> glob.matches(source) } }
            assertEquals(1, owners.size, "$source must have exactly one module owner, found ${owners.map(SourceModule::id)}")
        }
        catalog.modules.forEach { module ->
            module.sources.forEach { glob ->
                assertTrue(sources.any(glob::matches), "${module.id} source glob ${glob.pattern} matches no files")
            }
        }
    }

    @Test
    fun `module graph is complete unique and acyclic`() {
        val modulesById = catalog.modules.associateBy(SourceModule::id)
        assertEquals(catalog.modules.size, modulesById.size, "module ids must be unique")
        assertEquals(
            setOf(
                "kotlin:builtins",
                "stdlib:core",
                "stdlib:ranges",
                "std:terminal",
                "std:filesystem",
                "compukter:compiler",
                "compukter:process",
                "compukter:redstone",
            ),
            modulesById.keys,
        )
        assertTrue(modulesById.getValue("kotlin:builtins").dependencies.isEmpty())
        assertEquals("2.0.0", modulesById.getValue("compukter:redstone").version)
        catalog.modules.forEach { module ->
            assertTrue(MODULE_ID.matches(module.id), "invalid module id ${module.id}")
            assertTrue(VERSION.matches(module.version), "invalid module version ${module.version}")
            assertEquals(module.dependencies.size, module.dependencies.toSet().size, "${module.id} repeats dependencies")
            module.dependencies.forEach { dependency ->
                assertTrue(dependency in modulesById, "${module.id} depends on unknown module $dependency")
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            assertTrue(visiting.add(id), "module dependency cycle reaches $id")
            modulesById
                .getValue(id)
                .dependencies
                .filterNot(visited::contains)
                .forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        modulesById.keys.forEach { id -> if (id !in visited) visit(id) }
    }

    @Test
    fun `descriptor contains ownership infrastructure only`() {
        assertEquals(setOf("id", "version", "dependencies", "sources"), catalog.keys)
        assertEquals(
            catalog.modules.flatMap(SourceModule::sources).size,
            catalog.modules
                .flatMap(SourceModule::sources)
                .toSet()
                .size,
        )
        catalog.modules.flatMap(SourceModule::sources).forEach { source ->
            assertTrue(CANONICAL_SOURCE_GLOB.matches(source.pattern), "non-canonical source glob ${source.pattern}")
            assertFalse(".." in source.pattern, "source glob escapes the canonical root: ${source.pattern}")
        }
    }

    @Test
    fun `canonical inputs are platform neutral Kotlin sources`() {
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                val relative = root.relativize(path).invariantSeparatorsPathString
                assertFalse(relative.endsWith(".class"), "$relative is compiled JVM input")
                assertFalse(relative.endsWith(".jar"), "$relative is a JVM archive input")
                if (path.extension == "kt") {
                    val source = path.readText()
                    assertFalse(FOREIGN_REFERENCE.containsMatchIn(source), "$relative contains a foreign platform reference")
                    assertFalse("JvmInline" in source, "$relative contains JVM-only value-class syntax")
                }
            }
        }
    }

    @Test
    fun `source archive contains the exact canonical tree without classes`() {
        val archive = Path.of(checkNotNull(System.getProperty("compukters.platform.source-archive")))
        val expected =
            Files.walk(root).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .map { "compukters-platform/sources/${root.relativize(it).invariantSeparatorsPathString}" }
                    .toList()
                    .toSet()
            }
        ZipFile(archive.toFile()).use { zip ->
            val files =
                zip
                    .entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .toSet()
            assertEquals(expected, files - "META-INF/MANIFEST.MF")
            assertTrue(files.none { it.endsWith(".class") })
        }
    }

    private companion object {
        val MODULE_ID = Regex("[a-z][a-z0-9-]{0,63}:[a-z][a-z0-9-]{0,63}")
        val VERSION = Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+")
        val CANONICAL_SOURCE_GLOB = Regex("(?:builtins|libraries)/[a-z0-9-]+(?:/[a-z0-9-]+)*/\\*\\*/\\*\\.kt")
        val FOREIGN_REFERENCE = Regex("\\b(?:java|javax|kotlin\\.jvm|kotlin\\.js|kotlin\\.wasm)\\.")
    }
}

private data class SourceCatalog(
    val modules: List<SourceModule>,
    val keys: Set<String>,
) {
    companion object {
        fun parse(text: String): SourceCatalog {
            val modules = mutableListOf<MutableSourceModule>()
            val keys = mutableSetOf<String>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line == "[[module]]") {
                    modules += MutableSourceModule()
                    return@forEachIndexed
                }
                val module = modules.lastOrNull() ?: error("property before [[module]] at line ${index + 1}")
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', missingDelimiterValue = "").trim()
                require(value.isNotEmpty()) { "invalid descriptor property at line ${index + 1}" }
                require(module.keys.add(key)) { "duplicate descriptor key $key at line ${index + 1}" }
                keys += key
                when (key) {
                    "id" -> module.id = value.quoted()
                    "version" -> module.version = value.quoted()
                    "dependencies" -> module.dependencies = value.stringArray()
                    "sources" -> module.sources = value.stringArray().map(::SourceGlob)
                    else -> error("unsupported descriptor key $key at line ${index + 1}")
                }
            }
            return SourceCatalog(modules.map(MutableSourceModule::freeze), keys)
        }
    }
}

private data class SourceModule(
    val id: String,
    val version: String,
    val dependencies: List<String>,
    val sources: List<SourceGlob>,
)

private class MutableSourceModule {
    val keys = mutableSetOf<String>()
    var id: String? = null
    var version: String? = null
    var dependencies: List<String>? = null
    var sources: List<SourceGlob>? = null

    fun freeze(): SourceModule =
        SourceModule(
            id = requireNotNull(id) { "module id is missing" },
            version = requireNotNull(version) { "module version is missing" },
            dependencies = requireNotNull(dependencies) { "module dependencies are missing" },
            sources = requireNotNull(sources) { "module sources are missing" },
        )
}

private data class SourceGlob(
    val pattern: String,
) {
    private val regex = Regex(pattern.toRegexPattern())

    fun matches(path: String): Boolean = regex.matches(path)
}

private fun String.toRegexPattern(): String =
    buildString {
        append('^')
        var index = 0
        while (index < this@toRegexPattern.length) {
            when {
                this@toRegexPattern.startsWith("**/", index) -> {
                    append("(?:.*/)?")
                    index += 3
                }

                this@toRegexPattern.startsWith("**", index) -> {
                    append(".*")
                    index += 2
                }

                this@toRegexPattern[index] == '*' -> {
                    append("[^/]*")
                    index += 1
                }

                else -> {
                    append(Regex.escape(this@toRegexPattern[index].toString()))
                    index += 1
                }
            }
        }
        append('$')
    }

private fun String.quoted(): String {
    require(length >= 2 && first() == '"' && last() == '"') { "expected quoted string: $this" }
    return substring(1, lastIndex)
}

private fun String.stringArray(): List<String> {
    require(startsWith('[') && endsWith(']')) { "expected string array: $this" }
    val contents = substring(1, lastIndex).trim()
    return if (contents.isEmpty()) emptyList() else contents.split(',').map { it.trim().quoted() }
}
