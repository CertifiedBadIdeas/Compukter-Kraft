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

package ru.lazyhat.compukters.ide.analysis.k2.formatter

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal class IsolatedKotlinFormatter private constructor(
    private val loader: URLClassLoader,
    private val formatMethod: Method,
    private val temporaryRoot: Path?,
) : KotlinSourceFormatter,
    AutoCloseable {
    @Synchronized
    override fun format(
        fileName: String,
        source: String,
    ): String =
        try {
            formatMethod.invoke(null, fileName, source) as String
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }

    internal fun implementationClassLoader(): ClassLoader = formatMethod.declaringClass.classLoader

    override fun close() {
        loader.close()
        temporaryRoot?.let(::deleteTree)
    }

    companion object {
        private const val FORMATTER_CLASS = "ru.lazyhat.compukters.ide.formatter.KotlinFormatter"
        private const val RESOURCE_ROOT = "META-INF/compukters/kotlin-formatter/"

        fun packaged(): IsolatedKotlinFormatter {
            val container = Path.of(requireNotNull(IsolatedKotlinFormatter::class.java.protectionDomain.codeSource).location.toURI())
            require(container.isRegularFile()) { "analysis worker formatter container is not a JAR" }
            val temporaryRoot = createTempDirectory("compukters-kotlin-formatter-")
            return try {
                val paths = extractClasspath(container, temporaryRoot)
                open(paths, temporaryRoot)
            } catch (exception: Exception) {
                deleteTree(temporaryRoot)
                throw exception
            }
        }

        internal fun open(classpath: List<Path>): IsolatedKotlinFormatter = open(classpath, null)

        private fun open(
            classpath: List<Path>,
            temporaryRoot: Path?,
        ): IsolatedKotlinFormatter {
            require(classpath.isNotEmpty()) { "Kotlin formatter classpath is empty" }
            require(classpath.all(Path::isRegularFile)) { "Kotlin formatter classpath contains a missing file" }
            val loader = URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
            return try {
                val formatter = Class.forName(FORMATTER_CLASS, true, loader)
                val method = formatter.getMethod("format", String::class.java, String::class.java)
                IsolatedKotlinFormatter(loader, method, temporaryRoot)
            } catch (exception: Exception) {
                loader.close()
                throw exception
            }
        }

        private fun extractClasspath(
            container: Path,
            destination: Path,
        ): List<Path> {
            destination.createDirectories()
            JarFile(container.toFile()).use { jar ->
                val entries =
                    jar
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(RESOURCE_ROOT) && it.name.endsWith(".jar") }
                        .sortedBy { it.name }
                        .toList()
                require(entries.isNotEmpty()) { "analysis worker contains no embedded Kotlin formatter" }
                return entries.map { entry ->
                    val fileName = entry.name.removePrefix(RESOURCE_ROOT)
                    require('/' !in fileName && '\\' !in fileName && fileName.endsWith(".jar")) {
                        "Kotlin formatter resource path is invalid"
                    }
                    val target = destination.resolve(fileName).normalize()
                    require(target.parent == destination) { "Kotlin formatter resource escapes its destination" }
                    jar.getInputStream(entry).use { input -> Files.copy(input, target) }
                    target
                }
            }
        }

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
