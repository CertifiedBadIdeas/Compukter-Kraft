/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.sound

/** Emits bounded one-shot sounds from the computer. */
public object Sound {
    /**
     * Requests a note at the given volume.
     *
     * Rejected sounds are not queued. A request may be rejected by the per-computer or server-wide rate limit.
     *
     * @param note note-block pitch in the inclusive range `0..24`; `12` is neutral pitch.
     * @param volume volume percentage in the inclusive range `1..100`.
     * @return `true` when the server admits the sound, or `false` when it rejects the request.
     * @throws IllegalArgumentException if [note] or [volume] is outside its accepted range.
     */
    public fun beep(note: Int, volume: Int = 100): Boolean {
        if (note < 0 || note > 24) throw IllegalArgumentException("note must be between 0 and 24")
        if (volume < 1 || volume > 100) throw IllegalArgumentException("volume must be between 1 and 100")
        return SoundBindings.beep(note, volume)
    }
}

private object SoundBindings {
    external fun beep(note: Int, volume: Int): Boolean
}
