/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.filesystem

public object FileSystem {
    public external fun stat(path: String): Int

    public external fun list(path: String): String

    public external fun readText(path: String): String

    public external fun writeText(path: String, contents: String): Int
}
