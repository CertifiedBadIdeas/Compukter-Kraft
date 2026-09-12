/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** Starts on the caller's server thread; awaited actor replies resume on the server's result pump. */
fun <T> serverOperation(operation: suspend () -> T): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    operation.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(outcome: Result<T>) {
                outcome.fold(result::complete, result::completeExceptionally)
            }
        },
    )
    return result
}

suspend fun <T> CompletableFuture<T>.awaitServerResult(): T =
    suspendCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(
                    if (error is CompletionException) error.cause ?: error else error,
                )
            }
        }
    }
