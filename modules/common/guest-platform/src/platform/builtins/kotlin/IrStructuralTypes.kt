/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

internal interface Annotation

internal enum class DeprecationLevel {
    WARNING,
    ERROR,
    HIDDEN,
}
