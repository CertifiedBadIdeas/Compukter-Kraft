/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.reflect

import kotlin.*

internal interface KCallable<out R>

internal interface KProperty<out R> : KCallable<R>

internal interface KProperty0<out R> : KProperty<R>

internal interface KProperty1<in T, out R> : KProperty<R>

internal interface KProperty2<in D, in E, out R> : KProperty<R>

internal interface KMutableProperty0<R> : KProperty0<R>

internal interface KMutableProperty1<T, R> : KProperty1<T, R>

internal interface KMutableProperty2<D, E, R> : KProperty2<D, E, R>

internal interface KClass<out T : Any> : KCallable<T>

internal interface KType

internal interface KFunction<out R> : KCallable<R>, Function<R>
