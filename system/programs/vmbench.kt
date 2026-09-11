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

package compukter.system.vmbench

import compukter.io.Stderr
import compukter.redstone.Redstone

fun main(args: Array<String>) {
    if (args.size != 2) {
        writeUsage()
        return
    }
    val rounds = parseVmbenchRounds(args[1])
    if (rounds == 0) {
        writeUsage()
        return
    }

    if (args[0] == "cpu") {
        runCpu(rounds)
    } else if (args[0] == "redstone") {
        runRedstone(rounds)
    } else {
        writeUsage()
    }
}

private fun runCpu(rounds: Int) {
    print("vmbench cpu: rounds=")
    print(rounds)
    println(", iterations per round=1024")
    val checksum = runVmbenchCpu(rounds)
    print("vmbench cpu: checksum=")
    println(checksum)
}

private fun runRedstone(rounds: Int) {
    val transitions = rounds * 2
    print("vmbench redstone: rounds=")
    print(rounds)
    println(", transitions per round=2")

    Redstone.top.set(0)
    var round = 0
    while (round < rounds) {
        Redstone.top.set(15)
        Redstone.top.set(0)
        round = round + 1
    }

    print("vmbench redstone: acknowledged transitions=")
    println(transitions)
}

private fun writeUsage() {
    Stderr.write("usage: vmbench <cpu|redstone> <rounds 1..1000000>\n")
}
