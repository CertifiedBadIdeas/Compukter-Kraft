/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.terminal

public object Terminal {
    public external fun write(payload: String)

    public external fun erasePrevious()

    public external fun clear()

    public external fun awaitEvent(): Int

    public external fun eventText(): String

    public external fun eventKey(): Int

    public external fun eventAction(): Int

    public external fun eventModifiers(): Int

    public external fun finishEvent()

    public external fun setCursor(x: Int, y: Int)

    public external fun setCursorVisible(visible: Boolean)

    public external fun setColors(foreground: Int, background: Int)

    public external fun writeAt(x: Int, y: Int, text: String)

    public external fun fill(x: Int, y: Int, width: Int, height: Int, character: Char)
}
