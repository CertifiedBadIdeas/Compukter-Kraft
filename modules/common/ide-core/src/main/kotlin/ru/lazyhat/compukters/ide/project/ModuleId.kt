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

package ru.lazyhat.compukters.ide.project

data class ModuleId(
    val provider: String,
    val module: String,
) {
    init {
        require(COMPONENT.matches(provider)) { "invalid module provider: $provider" }
        require(COMPONENT.matches(module)) { "invalid module name: $module" }
    }

    val value: String get() = "$provider:$module"

    companion object {
        private val COMPONENT = Regex("[a-z][a-z0-9_-]{0,63}")

        fun parse(value: String): ModuleId {
            val separator = value.indexOf(':')
            require(separator > 0 && separator == value.lastIndexOf(':') && separator < value.lastIndex) {
                "module ID must have provider:module form"
            }
            return ModuleId(value.substring(0, separator), value.substring(separator + 1))
        }
    }
}

@JvmInline
value class ApiMajor(
    val value: Int,
) {
    init {
        require(value in 1..MAXIMUM) { "API major must be between 1 and $MAXIMUM" }
    }

    companion object {
        const val MAXIMUM = 65_535
    }
}
