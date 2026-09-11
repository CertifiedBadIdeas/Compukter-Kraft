/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.sound

public object Sound {
    public fun beep(note: Int): Boolean = beep(note, 100)

    public fun beep(note: Int, volume: Int): Boolean {
        if (note < 0 || note > 24) throw IllegalArgumentException("note must be between 0 and 24")
        if (volume < 1 || volume > 100) throw IllegalArgumentException("volume must be between 1 and 100")
        return SoundBindings.beep(note, volume)
    }
}

private object SoundBindings {
    external fun beep(note: Int, volume: Int): Boolean
}
