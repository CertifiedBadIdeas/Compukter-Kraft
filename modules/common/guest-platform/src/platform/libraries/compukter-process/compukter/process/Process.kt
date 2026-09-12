/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.process

public object Process {
    public fun run(path: String): ProcessResult = run(path, arrayOf<String>())

    public fun run(path: String, args: Array<String>): ProcessResult {
        val raw = ProcessBindings.run(path, encodeArgs(args))
        if (raw >= 0) return ProcessResult.Exited(raw)
        return ProcessResult.Failed(failureReason(-raw), ProcessBindings.takeFailureDiagnostic())
    }

    public fun exit(code: Int): Nothing = ProcessBindings.exit(code)
}

public sealed interface ProcessResult {
    public data class Exited(val code: Int) : ProcessResult

    public data class Failed(val reason: ProcessFailureReason, val diagnostic: String) : ProcessResult
}

public enum class ProcessFailureReason {
    INVALID_PATH,
    NOT_FOUND,
    ACCESS_DENIED,
    NOT_EXECUTABLE,
    INVALID_PROGRAM,
    INCOMPATIBLE,
    LIMIT_EXCEEDED,
    TRAPPED,
    VM_FAULT,
    HOST_FAILURE,
    IO_FAILURE,
}

private object ProcessBindings {
    external fun run(path: String, encodedArgs: String): Int

    external fun takeFailureDiagnostic(): String

    external fun exit(code: Int): Nothing
}

private fun failureReason(code: Int): ProcessFailureReason =
    when (code) {
        1 -> ProcessFailureReason.INVALID_PATH
        2 -> ProcessFailureReason.NOT_FOUND
        3 -> ProcessFailureReason.ACCESS_DENIED
        4 -> ProcessFailureReason.NOT_EXECUTABLE
        5 -> ProcessFailureReason.INVALID_PROGRAM
        6 -> ProcessFailureReason.INCOMPATIBLE
        7 -> ProcessFailureReason.LIMIT_EXCEEDED
        8 -> ProcessFailureReason.TRAPPED
        9 -> ProcessFailureReason.VM_FAULT
        10 -> ProcessFailureReason.HOST_FAILURE
        11 -> ProcessFailureReason.IO_FAILURE
        else -> ProcessBindings.exit(-1)
    }

private fun encodeArgs(args: Array<String>): String {
    var size = 2
    var index = 0
    while (index < args.size) {
        val length = args[index].length
        if (size > Int.MAX_VALUE - 2 - length) ProcessBindings.exit(-1)
        size = size + 2 + length
        index = index + 1
    }

    val encoded = CharArray(size)
    encoded[0] = (args.size % 65536).toChar()
    encoded[1] = (args.size / 65536).toChar()
    var output = 2
    index = 0
    while (index < args.size) {
        val argument = args[index]
        encoded[output] = (argument.length % 65536).toChar()
        encoded[output + 1] = (argument.length / 65536).toChar()
        output = output + 2
        var input = 0
        while (input < argument.length) {
            encoded[output] = argument[input]
            output = output + 1
            input = input + 1
        }
        index = index + 1
    }
    return String(encoded, 0, encoded.size)
}
