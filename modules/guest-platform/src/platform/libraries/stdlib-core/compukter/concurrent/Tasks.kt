/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.concurrent

/** A bounded cooperative Guest task. */
public value class Task internal constructor(internal val id: Int) {
    /** Suspends the current task until this task completes. */
    public external suspend fun join()
}

/** Starts bounded cooperative work within the current VM. */
public object Tasks {
    /**
     * Starts [block] and returns its task handle.
     *
     * Compukters currently accepts only a direct reference to a top-level, zero-argument suspend function.
     */
    public external fun launch(block: suspend () -> Unit): Task
}

/**
 * A bounded FIFO channel carrying [Int] values between cooperative Guest tasks.
 *
 * Declare channels as top-level immutable properties. [send] suspends while the channel is full,
 * and [receive] suspends while it is empty. A positive [capacity] is reserved when the program is admitted.
 */
public value class IntChannel public constructor(internal val capacity: Int) {
    /** Sends [value], suspending until bounded channel storage is available. */
    public external suspend fun send(value: Int)

    /** Receives the oldest queued value, suspending until one is available. */
    public external suspend fun receive(): Int
}
