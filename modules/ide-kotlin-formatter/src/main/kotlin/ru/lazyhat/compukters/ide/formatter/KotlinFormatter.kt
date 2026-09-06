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

package ru.lazyhat.compukters.ide.formatter

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.nio.file.Path

object KotlinFormatter {
    private val engine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders())

    @JvmStatic
    fun format(
        fileName: String,
        source: String,
    ): String {
        require(fileName.endsWith(".kt")) { "formatter path must name a Kotlin source" }
        val code = Code.fromSnippetWithPath(source, Path.of(fileName).fileName)
        return engine.format(code) { _, _ -> }
    }
}
