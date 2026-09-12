/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.io

internal object StdioBindings {
    external fun write(payload: String)
}

internal fun stdoutPrint(value: String): Unit = StdioBindings.write(value)

internal fun stdoutPrint(value: Int): Unit = StdioBindings.write(stdoutInt(value))

internal fun stdoutPrint(value: Boolean): Unit = StdioBindings.write(if (value) "true" else "false")

internal fun stdoutPrint(value: Char): Unit = StdioBindings.write(stdoutChar(value))

internal fun stdoutPrintln(): Unit = StdioBindings.write("\n")

internal fun stdoutPrintln(value: String): Unit = StdioBindings.write(value + "\n")

internal fun stdoutPrintln(value: Int): Unit = StdioBindings.write(stdoutInt(value) + "\n")

internal fun stdoutPrintln(value: Boolean): Unit = StdioBindings.write(if (value) "true\n" else "false\n")

internal fun stdoutPrintln(value: Char): Unit = StdioBindings.write(stdoutChar(value) + "\n")

private fun stdoutChar(value: Char): String {
    val contents = CharArray(1)
    contents[0] = value
    return String(contents, 0, 1)
}

private fun stdoutInt(value: Int): String {
    if (value == 0) return "0"
    val contents = CharArray(11)
    var cursor = 11
    var remaining = if (value > 0) 0 - value else value
    while (remaining != 0) {
        val digit = 0 - (remaining % 10)
        cursor = cursor - 1
        contents[cursor] = (48 + digit).toChar()
        remaining = remaining / 10
    }
    if (value < 0) {
        cursor = cursor - 1
        contents[cursor] = '-'
    }
    return String(contents, cursor, 11 - cursor)
}
