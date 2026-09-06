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

package ru.lazyhat.compukters.compiler.k2.engine.intrinsic

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId

object CanonicalTrustedIntrinsics {
    val terminal = PlatformCapabilityId("compukter", "terminal", 2)
    val stdio = PlatformCapabilityId("compukter", "stdio", 1)
    val process = PlatformCapabilityId("compukter", "process", 2)
    val filesystem = PlatformCapabilityId("compukter", "filesystem", 1)
    val compiler = PlatformCapabilityId("compukter", "compiler", 1)
    val redstone = PlatformCapabilityId("compukter", "redstone", 1)

    val executableCapabilities: Set<PlatformCapabilityId> =
        setOf(terminal, stdio, process, filesystem, compiler, redstone)

    val registry: TrustedIntrinsicRegistry = TrustedIntrinsicRegistry.create(registrations())

    private fun registrations(): List<TrustedIntrinsicRegistration> =
        buildList {
            primitive("kotlin", "builtins", "kotlin", "Any.equals", "fun(Any?):Boolean")
            primitive("kotlin", "builtins", "kotlin", "Any.hashCode", "fun():Int")
            primitive("kotlin", "builtins", "kotlin", "Any.toString", "fun():String")
            primitive("kotlin", "builtins", "kotlin", "Array.get", "fun(Int):T")
            primitive("kotlin", "builtins", "kotlin", "Array.set", "fun(Int,T):Unit")
            primitive("kotlin", "builtins", "kotlin", "Array.size", "val():Int")
            primitive("kotlin", "builtins", "kotlin", "Boolean.compareTo", "fun(Boolean):Int")
            primitive("kotlin", "builtins", "kotlin", "Boolean.not", "fun():Boolean")
            primitive("kotlin", "builtins", "kotlin", "Char.compareTo", "fun(Char):Int")
            primitive("kotlin", "builtins", "kotlin", "Char.toInt", "fun():Int")
            primitive("kotlin", "builtins", "kotlin", "CharArray.<init>", "constructor(Int)")
            primitive("kotlin", "builtins", "kotlin", "CharArray.get", "fun(Int):Char")
            primitive("kotlin", "builtins", "kotlin", "CharArray.set", "fun(Int,Char):Unit")
            primitive("kotlin", "builtins", "kotlin", "CharArray.size", "val():Int")
            primitive("kotlin", "builtins", "kotlin", "IntArray.<init>", "constructor(Int)")
            primitive("kotlin", "builtins", "kotlin", "IntArray.get", "fun(Int):Int")
            primitive("kotlin", "builtins", "kotlin", "IntArray.set", "fun(Int,Int):Unit")
            primitive("kotlin", "builtins", "kotlin", "IntArray.size", "val():Int")
            primitive("kotlin", "builtins", "kotlin", "CharSequence.get", "fun(Int):Char")
            primitive("kotlin", "builtins", "kotlin", "Comparable.compareTo", "fun(T):Int")
            primitive("kotlin", "builtins", "kotlin", "Enum.name", "val():String")
            primitive("kotlin", "builtins", "kotlin", "Enum.ordinal", "val():Int")
            primitive("kotlin", "builtins", "kotlin", "Enum.<init>", "constructor()")
            primitive("kotlin", "builtins", "kotlin", "Throwable.<init>", "constructor(String?)")
            primitive("kotlin", "builtins", "kotlin", "Exception.<init>", "constructor(String?)")
            primitive("kotlin", "builtins", "kotlin", "RuntimeException.<init>", "constructor(String?)")
            primitive("kotlin", "builtins", "kotlin", "IllegalArgumentException.<init>", "constructor(String?)")
            primitive("kotlin", "builtins", "kotlin", "Function0.invoke", "fun():R")
            primitive("kotlin", "builtins", "kotlin", "Function1.invoke", "fun(P1):R")
            primitive("kotlin", "builtins", "kotlin", "Function2.invoke", "fun(P1,P2):R")
            listOf(
                "and" to "fun(Int):Int",
                "compareTo" to "fun(Int):Int",
                "div" to "fun(Int):Int",
                "inv" to "fun():Int",
                "minus" to "fun(Int):Int",
                "or" to "fun(Int):Int",
                "plus" to "fun(Int):Int",
                "rem" to "fun(Int):Int",
                "shl" to "fun(Int):Int",
                "shr" to "fun(Int):Int",
                "times" to "fun(Int):Int",
                "toChar" to "fun():Char",
                "unaryMinus" to "fun():Int",
                "ushr" to "fun(Int):Int",
                "xor" to "fun(Int):Int",
            ).forEach { (name, signature) -> primitive("kotlin", "builtins", "kotlin", "Int.$name", signature) }
            primitive("kotlin", "builtins", "kotlin", "String.<init>", "constructor()")
            primitive("kotlin", "builtins", "kotlin", "String.<init>", "constructor(CharArray)")
            primitive("kotlin", "builtins", "kotlin", "String.<init>", "constructor(CharArray,Int,Int)")
            primitive("kotlin", "builtins", "kotlin", "String.compareTo", "fun(String):Int")
            primitive("kotlin", "builtins", "kotlin", "String.get", "fun(Int):Char")
            primitive("kotlin", "builtins", "kotlin", "String.length", "val():Int")
            primitive("kotlin", "builtins", "kotlin", "String.plus", "fun(Any?):String")
            primitive("kotlin", "builtins", "kotlin", "String.substring", "fun(Int,Int):String")
            primitive("kotlin", "builtins", "kotlin", "Unit.toString", "fun():String")
            primitive("kotlin", "builtins", "kotlin", "arrayOf", "fun(T):Array<T>")
            primitive("kotlin", "builtins", "kotlin", "arrayOfNulls", "fun(Int):Array<T?>")
            primitive("kotlin", "builtins", "kotlin", "intArrayOf", "fun(Int):IntArray")
            primitive("kotlin", "builtins", "kotlin", "toString", "fun(T?.):String")
            primitive(
                "kotlin",
                "builtins",
                "kotlin.collections",
                "copyOfRange",
                "fun(Array<T>.Int,Int):Array<T>",
            )
            primitive(
                "kotlin",
                "builtins",
                "kotlin.text",
                "concatToString",
                "fun(CharArray.Int,Int):String",
            )
            primitive("stdlib", "core", "kotlin", "emptyArray", "fun():Array<T>")
            primitive("stdlib", "ranges", "kotlin.ranges", "IntRange.iterator", "fun():IntIterator")
            primitive("stdlib", "ranges", "kotlin.ranges", "rangeUntil", "fun(Int.Int):IntRange")
            primitive("stdlib", "ranges", "kotlin.ranges", "until", "fun(Int.Int):IntRange")

            capability("compukter", "compiler", "compukter.compiler", "Compiler.compile", "fun(String,String):Int", compiler, 0u, true)
            capability("compukter", "compiler", "compukter.compiler", "Compiler.diagnostics", "fun():String", compiler, 1u)
            capability("compukter", "process", "compukter.process", "ProcessBindings.run", "fun(String,String):Int", process, 0u, true)
            capability("compukter", "process", "compukter.process", "ProcessBindings.takeFailureDiagnostic", "fun():String", process, 1u)
            capability(
                "compukter",
                "process",
                "compukter.process",
                "ProcessBindings.exit",
                "fun(Int):Nothing",
                process,
                2u,
                terminalCall = true,
            )

            listOf(
                Triple("input", "fun(Int):Int", false),
                Triple("awaitInputChange", "fun(Int):Int", true),
                Triple("awaitInput", "fun(Int,Int):Int", true),
                Triple("awaitAtLeastInput", "fun(Int,Int):Int", true),
                Triple("awaitAtMostInput", "fun(Int,Int):Int", true),
                Triple("outputs", "fun():Int", false),
                Triple("setOutput", "fun(Int,Int):Unit", true),
                Triple("setOutputs", "fun(Int):Unit", true),
            ).forEachIndexed { operation, (name, signature, blocking) ->
                capability(
                    "compukter",
                    "redstone",
                    "compukter.redstone",
                    "RedstoneBindings.$name",
                    signature,
                    redstone,
                    operation.toUInt(),
                    blocking,
                )
            }

            listOf(
                Triple("stat", "fun(String):Int", 0u),
                Triple("list", "fun(String):String", 1u),
                Triple("readText", "fun(String):String", 2u),
                Triple("writeText", "fun(String,String):Int", 3u),
            ).forEach { (name, signature, operation) ->
                capability("std", "filesystem", "compukter.filesystem", "FileSystem.$name", signature, filesystem, operation)
            }

            capability("std", "terminal", "compukter.io", "Stderr.write", "fun(String):Unit", stdio, 2u)
            capability("std", "terminal", "compukter.io", "StdioBindings.write", "fun(String):Unit", stdio, 1u)
            val terminalOperations =
                listOf(
                    Triple("write", "fun(String):Unit", false),
                    Triple("erasePrevious", "fun():Unit", false),
                    Triple("clear", "fun():Unit", false),
                    Triple("awaitEvent", "fun():Int", true),
                    Triple("eventText", "fun():String", false),
                    Triple("eventKey", "fun():Int", false),
                    Triple("eventAction", "fun():Int", false),
                    Triple("eventModifiers", "fun():Int", false),
                    Triple("finishEvent", "fun():Unit", false),
                    Triple("setCursor", "fun(Int,Int):Unit", false),
                    Triple("setCursorVisible", "fun(Boolean):Unit", false),
                    Triple("setColors", "fun(Int,Int):Unit", false),
                    Triple("writeAt", "fun(Int,Int,String):Unit", false),
                    Triple("fill", "fun(Int,Int,Int,Int,Char):Unit", false),
                )
            terminalOperations.forEachIndexed { operation, (name, signature, blocking) ->
                capability("std", "terminal", "compukter.terminal", "Terminal.$name", signature, terminal, operation.toUInt(), blocking)
            }
            capability("std", "terminal", "kotlin.io", "readln", "fun():String", stdio, 0u, true)
        }

    private fun MutableList<TrustedIntrinsicRegistration>.primitive(
        namespace: String,
        module: String,
        packageName: String,
        path: String,
        signature: String,
    ) = register(namespace, module, packageName, path, signature, CompilerPrimitiveHandler("$packageName.$path"))

    private fun MutableList<TrustedIntrinsicRegistration>.capability(
        namespace: String,
        module: String,
        packageName: String,
        path: String,
        signature: String,
        capability: PlatformCapabilityId,
        operation: UInt,
        blocking: Boolean = false,
        terminalCall: Boolean = false,
    ) = register(
        namespace,
        module,
        packageName,
        path,
        signature,
        CapabilityOperationHandler(
            capability,
            operation,
            if (blocking) IntrinsicBlockingMode.VM_TASK else IntrinsicBlockingMode.NONE,
            terminalCall,
        ),
    )

    private fun MutableList<TrustedIntrinsicRegistration>.register(
        namespace: String,
        module: String,
        packageName: String,
        path: String,
        signature: String,
        handler: TrustedIntrinsicHandler,
    ) {
        val parts = path.split('.')
        val callable = parts.last()
        val callableName = if (callable.startsWith('<')) Name.special(callable) else Name.identifier(callable)
        val className = parts.dropLast(1).joinToString(".").takeIf(String::isNotEmpty)
        add(
            TrustedIntrinsicRegistration(
                TrustedIntrinsicKey(
                    PlatformModuleId(namespace, module),
                    if (className == null) {
                        CallableId(FqName(packageName), callableName)
                    } else {
                        CallableId(FqName(packageName), FqName(className), callableName)
                    },
                    CanonicalCallableSignature(signature),
                ),
                handler,
            ),
        )
    }
}
