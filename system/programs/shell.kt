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

package compukter.system.shell

import compukter.filesystem.FileSystem
import compukter.io.Stderr
import compukter.process.Process
import compukter.process.ProcessFailureReason
import compukter.process.ProcessResult
import compukter.terminal.Terminal

fun main() {
    while (true) {
        print("> ")
        dispatch(readln())
    }
}

private fun dispatch(line: String) {
    when (val result = lex(line)) {
        is LexResult.Error -> Stderr.write(result.message + "\n")
        is LexResult.Success -> dispatchWords(result.words)
    }
}

private fun dispatchWords(words: Array<String>) {
    if (words.size == 0) return
    val command = words[0]
    if (command == "help") {
        println("help echo clear pwd ls stat kotlinc edit vmbench")
    } else if (command == "echo") {
        var index = 1
        while (index < words.size) {
            if (index > 1) print(" ")
            print(words[index])
            index = index + 1
        }
        println()
    } else if (command == "clear") {
        if (words.size == 1) Terminal.clear() else Stderr.write("usage: clear\n")
    } else if (command == "pwd") {
        if (words.size == 1) println("/home") else Stderr.write("usage: pwd\n")
    } else if (command == "ls") {
        if (words.size == 1) writeList("/home")
        else if (words.size == 2 && words[1] != "") writeList(resolvePath(words[1]))
        else Stderr.write("usage: ls [path]\n")
    } else if (command == "stat") {
        if (words.size == 2 && words[1] != "") writeStat(resolvePath(words[1]))
        else Stderr.write("usage: stat <path>\n")
    } else if (command == "") {
        Stderr.write("empty command\n")
    } else {
        executeExternal(words)
    }
}

private fun executeExternal(words: Array<String>) {
    val command = words[0]
    val arguments = words.copyOfRange(1, words.size)
    var path = if (command[0] == '/') command else "/home/" + command
    var result = Process.run(path, arguments)
    val fallback = shellFallbackPath(command, result)
    if (fallback != "") {
        path = fallback
        result = Process.run(path, arguments)
    }
    val diagnostic = shellProcessDiagnostic(result, path)
    if (diagnostic != "") Stderr.write(diagnostic + "\n")
}

fun shellFallbackPath(
    command: String,
    result: ProcessResult,
): String {
    if (command == "" || command[0] == '/') return ""
    if (result is ProcessResult.Failed && result.reason == ProcessFailureReason.NOT_FOUND) {
        return "/rom/" + command
    }
    return ""
}

private fun resolvePath(path: String): String = if (path[0] == '/') path else "/home/" + path

private fun writeList(path: String) {
    val kind = FileSystem.stat(path)
    if (kind == 1) {
        println(path)
    } else if (kind == 2) {
        val names = FileSystem.list(path)
        var start = 0
        while (start < names.length) {
            var end = start
            while (end < names.length && names[end] != '\u0000') end = end + 1
            if (end != start) println(names.substring(start, end))
            start = end + 1
        }
    } else {
        Stderr.write(fileSystemDiagnostic(kind, path) + "\n")
    }
}

private fun writeStat(path: String) {
    val kind = FileSystem.stat(path)
    if (kind == 1) println("file: " + path)
    else if (kind == 2) println("directory: " + path)
    else Stderr.write(fileSystemDiagnostic(kind, path) + "\n")
}

private fun fileSystemDiagnostic(
    result: Int,
    path: String,
): String {
    if (result == -1) return "invalid path: " + path
    if (result == -2) return "not found: " + path
    if (result == -3) return "already exists: " + path
    if (result == -4) return "not a directory: " + path
    if (result == -5) return "is a directory: " + path
    if (result == -6) return "directory not empty: " + path
    if (result == -7) return "read-only filesystem: " + path
    if (result == -8) return "permission denied: " + path
    if (result == -9) return "stale file handle: " + path
    if (result == -10) return "filesystem quota exceeded"
    if (result == -11) return "filesystem busy"
    if (result == -12) return "filesystem unavailable"
    if (result == -13) return "filesystem closed"
    if (result == -14) return "not executable: " + path
    return "filesystem error"
}

fun shellProcessDiagnostic(
    result: ProcessResult,
    path: String,
): String {
    if (result is ProcessResult.Exited) {
        if (result.code == 0) return ""
        return path + ": exited with an error"
    }
    if (result is ProcessResult.Failed) {
        if (result.diagnostic != "") return result.diagnostic
        if (result.reason == ProcessFailureReason.INVALID_PATH) return "invalid path: " + path
        if (result.reason == ProcessFailureReason.NOT_FOUND) return "command not found: " + path
        if (result.reason == ProcessFailureReason.ACCESS_DENIED) return "permission denied: " + path
        if (result.reason == ProcessFailureReason.NOT_EXECUTABLE) return "not executable: " + path
        if (result.reason == ProcessFailureReason.INVALID_PROGRAM) return "invalid executable: " + path
        if (result.reason == ProcessFailureReason.INCOMPATIBLE) return "incompatible program: " + path
        if (result.reason == ProcessFailureReason.LIMIT_EXCEEDED) return "process limit exceeded"
        if (result.reason == ProcessFailureReason.TRAPPED) return "program trapped: " + path
        if (result.reason == ProcessFailureReason.VM_FAULT) return "virtual machine fault"
        if (result.reason == ProcessFailureReason.HOST_FAILURE) return "host capability failed"
        return "I/O error: " + path
    }
    return "process failed"
}
