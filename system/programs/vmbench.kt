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

fun main(args: Array<String>) {
    if (args.size != 2 || args[0] != "cpu") {
        writeUsage()
        return
    }
    val rounds = parseRounds(args[1])
    if (rounds == 0) {
        writeUsage()
        return
    }

    print("vmbench cpu: rounds=")
    print(rounds)
    println(", iterations per round=1024")
    val checksum = runCpu(rounds)
    print("vmbench cpu: checksum=")
    println(checksum)
}

private fun parseRounds(text: String): Int {
    val maximumRounds = 1_000_000
    if (text.length == 0) return 0
    var value = 0
    var index = 0
    while (index < text.length) {
        val digit =
            when (text[index]) {
                '0' -> 0
                '1' -> 1
                '2' -> 2
                '3' -> 3
                '4' -> 4
                '5' -> 5
                '6' -> 6
                '7' -> 7
                '8' -> 8
                '9' -> 9
                else -> return 0
            }
        if (value > (maximumRounds - digit) / 10) return 0
        value = value * 10 + digit
        index = index + 1
    }
    if (value == 0) return 0
    return value
}

private fun runCpu(rounds: Int): Int {
    var checksum = 324_508_639
    var round = 0
    while (round < rounds) {
        var lane = checksum xor round
        var iteration = 0
        while (iteration < 1_024) {
            lane = lane * 1_664_525 + 1_013_904_223
            lane = lane xor (lane ushr 16)
            if ((lane and 1) == 0) {
                checksum = checksum + lane
            } else {
                checksum = checksum xor lane
            }
            iteration = iteration + 1
        }
        checksum = checksum xor round
        round = round + 1
    }
    return checksum
}

private fun writeUsage() {
    Stderr.write("usage: vmbench cpu <rounds 1..1000000>\n")
}
