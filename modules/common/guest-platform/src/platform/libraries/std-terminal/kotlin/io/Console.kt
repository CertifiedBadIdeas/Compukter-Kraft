/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.io

import compukter.io.stdoutPrint
import compukter.io.stdoutPrintln

public fun print(value: String): Unit = stdoutPrint(value)

public fun print(value: Int): Unit = stdoutPrint(value)

public fun print(value: Boolean): Unit = stdoutPrint(value)

public fun print(value: Char): Unit = stdoutPrint(value)

public fun println(): Unit = stdoutPrintln()

public fun println(value: String): Unit = stdoutPrintln(value)

public fun println(value: Int): Unit = stdoutPrintln(value)

public fun println(value: Boolean): Unit = stdoutPrintln(value)

public fun println(value: Char): Unit = stdoutPrintln(value)

public external fun readln(): String
