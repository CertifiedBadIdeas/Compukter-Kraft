/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.IntIterator

public class IntRange(
    public val first: Int,
    public val last: Int,
) {
    public operator fun contains(value: Int): Boolean = value >= first && value <= last

    public external operator fun iterator(): IntIterator
}

public operator fun Int.rangeTo(other: Int): IntRange = IntRange(this, other)

public external infix fun Int.until(other: Int): IntRange

public external operator fun Int.rangeUntil(other: Int): IntRange
