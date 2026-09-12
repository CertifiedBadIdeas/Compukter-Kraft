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
