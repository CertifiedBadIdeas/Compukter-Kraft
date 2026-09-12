/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public fun require(value: Boolean) {
    if (!value) throw IllegalArgumentException("Failed requirement.")
}

public external fun <T> emptyArray(): Array<T>
