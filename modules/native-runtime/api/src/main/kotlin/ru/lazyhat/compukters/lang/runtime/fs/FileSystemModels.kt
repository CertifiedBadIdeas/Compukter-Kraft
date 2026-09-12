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

package ru.lazyhat.compukters.lang.runtime.fs

enum class FileSystemStoreHealth(
    internal val wireCode: Int,
) {
    ACTIVE(0),
    DRAINING(1),
    FAULTED(2),
    CLOSED(3),
}

enum class FileSystemStoreOpenFailure(
    internal val wireCode: Int,
) {
    ROOT_NOT_ABSOLUTE(1),
    ROOT_NOT_CANONICAL(2),
    ROOT_NOT_DIRECTORY(3),
    LOCKED(4),
    IO(5),
}

class FileSystemStoreOpenException(
    val failure: FileSystemStoreOpenFailure,
) : IllegalStateException("native filesystem store admission failed: $failure")
