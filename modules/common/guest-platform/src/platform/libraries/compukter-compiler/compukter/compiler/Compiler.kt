/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.compiler

public object Compiler {
    public external fun compile(source: String, output: String): Int

    public external fun diagnostics(): String
}
