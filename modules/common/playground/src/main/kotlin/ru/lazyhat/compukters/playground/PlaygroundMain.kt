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

package ru.lazyhat.compukters.playground

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    VmRuntime.install(FfmRuntimeBackend)
    val debug = "--debug" in arguments
    val exit =
        try {
            val payload = Path.of(requiredProperty("compukters.worker.payload"))
            val library = Path.of(requiredProperty("compukters.ffi.library"))
            val java =
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
                )
            val application =
                PlaygroundApplication(
                    compilerFactory = { ForkedPlaygroundCompiler(payload, java) },
                    executor = NativePlaygroundExecutor(library, System.`in`, System.out),
                    stderr = System.err,
                )
            runBlocking { application.run(arguments.toList()) }
        } catch (exception: Exception) {
            System.err.println("launcher: ${exception.message}")
            if (debug) exception.printStackTrace(System.err)
            PlaygroundExit.PLATFORM
        }
    exitProcess(exit)
}

private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing system property $name" }
