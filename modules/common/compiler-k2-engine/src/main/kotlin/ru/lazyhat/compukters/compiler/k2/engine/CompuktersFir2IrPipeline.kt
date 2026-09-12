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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.backend.Fir2IrConfiguration
import org.jetbrains.kotlin.fir.backend.Fir2IrExtensions
import org.jetbrains.kotlin.fir.backend.Fir2IrVisibilityConverter
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.Fir2IrActualizedResult
import org.jetbrains.kotlin.fir.pipeline.convertToIrAndActualize
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirModuleOutput

/** Converts resolved Compukters FIR through the common K2 FIR-to-IR implementation. */
@OptIn(CompilerConfiguration.Internals::class)
object CompuktersFir2IrPipeline {
    fun convert(outputs: List<CompuktersFirModuleOutput>): Fir2IrActualizedResult {
        require(outputs.isNotEmpty()) { "Compukters FIR-to-IR requires at least one module" }
        val diagnostics = DiagnosticsCollectorImpl()
        val configuration =
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl.DEFAULT)
            }
        return AllModulesFrontendOutput(outputs.map(CompuktersFirModuleOutput::frontendOutput)).convertToIrAndActualize(
            fir2IrExtensions = Fir2IrExtensions.Default,
            fir2IrConfiguration = Fir2IrConfiguration.forKlibCompilation(configuration, diagnostics),
            irGeneratorExtensions = emptyList(),
            irMangler = CompuktersIrMangler,
            visibilityConverter = Fir2IrVisibilityConverter.Default,
            kotlinBuiltIns = DefaultBuiltIns.Instance,
            typeSystemContextProvider = { irBuiltIns -> IrTypeSystemContextImpl(irBuiltIns) },
            specialAnnotationsProvider = null,
            extraActualDeclarationExtractorsInitializer = { emptyList() },
        )
    }

    fun lowerGuest(
        output: CompuktersFirModuleOutput,
        session: CompilationSession,
    ): Artifact? {
        val converted = convert(listOf(output))
        session.irSink.accept(converted.irModuleFragment, converted.pluginContext)
        return MinimalScriptLowering.lower(converted.irModuleFragment, converted.pluginContext, session)
    }
}
