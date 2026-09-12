/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public interface Function<out R>

public interface Function0<out R> : Function<R> {
    public external operator fun invoke(): R
}

public interface Function1<in P1, out R> : Function<R> {
    public external operator fun invoke(p1: P1): R
}

public interface Function2<in P1, in P2, out R> : Function<R> {
    public external operator fun invoke(p1: P1, p2: P2): R
}
