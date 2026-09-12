/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.redstone

public value class RedstoneSide internal constructor(internal val index: Int) {
    init {
        require(index in 0..5)
    }

    public fun get(): Int = redstoneLevel(RedstoneBindings.input(index))

    public fun set(level: Int, power: Redstone.Power = Redstone.Power.WEAK) {
        RedstoneBindings.setOutput(index, redstoneOutput(level, power))
    }

    public fun await(): Int = redstoneLevel(RedstoneBindings.awaitInputChange(index))

    public fun await(level: Int): Int = redstoneLevel(RedstoneBindings.awaitInput(index, redstoneLevel(level)))

    public fun awaitAtLeast(level: Int): Int =
        redstoneLevel(RedstoneBindings.awaitAtLeastInput(index, redstoneLevel(level)))

}

public object Redstone {
    public enum class Power {
        WEAK,
        DIRECT,
    }

    public val front: RedstoneSide
        get() = RedstoneSide(0)

    public val back: RedstoneSide
        get() = RedstoneSide(1)

    public val left: RedstoneSide
        get() = RedstoneSide(2)

    public val right: RedstoneSide
        get() = RedstoneSide(3)

    public val top: RedstoneSide
        get() = RedstoneSide(4)

    public val bottom: RedstoneSide
        get() = RedstoneSide(5)
}

private object RedstoneBindings {
    external fun input(side: Int): Int

    external fun awaitInputChange(side: Int): Int

    external fun awaitInput(side: Int, signal: Int): Int

    external fun awaitAtLeastInput(side: Int, signal: Int): Int

    external fun awaitAtMostInput(side: Int, signal: Int): Int

    external fun outputs(): Int

    external fun setOutput(side: Int, output: Int)

    external fun setOutputs(outputs: Int)
}

private value class RedstoneLevel(private val value: Int) {
    init {
        require(value in 0..15)
    }

    public fun asInt(): Int = value
}

private fun redstoneLevel(level: Int): Int = RedstoneLevel(level).asInt()

private fun redstoneOutput(level: Int, power: Redstone.Power): Int =
    redstoneLevel(level) or if (power == Redstone.Power.DIRECT) 0x10 else 0
