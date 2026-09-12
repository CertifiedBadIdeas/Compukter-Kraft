/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public open class Any {
    public external open operator fun equals(other: Any?): Boolean

    public external open fun hashCode(): Int

    public external open fun toString(): String
}

public class Nothing private constructor()

public object Unit {
    public external override fun toString(): String
}

public interface Comparable<in T> {
    public external operator fun compareTo(other: T): Int
}

public interface CharSequence {
    public val length: Int

    public external operator fun get(index: Int): Char
}

public open class Throwable external constructor(message: String?)

public open class Exception external constructor(message: String?) : Throwable(message)

public open class RuntimeException external constructor(message: String?) : Exception(message)

public class IllegalArgumentException external constructor(message: String?) : RuntimeException(message)

public abstract class Enum<E : Enum<E>> external constructor() : Comparable<E> {
    public external val name: String

    public external val ordinal: Int
}

public external fun <T> T?.toString(): String
