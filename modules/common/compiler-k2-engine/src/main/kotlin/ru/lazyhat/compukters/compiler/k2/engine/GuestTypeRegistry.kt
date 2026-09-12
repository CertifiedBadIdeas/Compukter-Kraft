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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.isNullable

internal class GuestTypeRegistry(
    pluginContext: IrPluginContext,
) {
    val stringType: IrType = pluginContext.irBuiltIns.stringType
    val stringArrayClass: IrClassSymbol = pluginContext.irBuiltIns.arrayClass

    fun isStringArray(type: IrType): Boolean {
        val simple = type as? IrSimpleType ?: return false
        if (simple.isNullable() || simple.classifier != stringArrayClass) return false
        val argument = simple.arguments.singleOrNull() as? IrTypeProjection ?: return false
        return argument.type == stringType
    }
}
