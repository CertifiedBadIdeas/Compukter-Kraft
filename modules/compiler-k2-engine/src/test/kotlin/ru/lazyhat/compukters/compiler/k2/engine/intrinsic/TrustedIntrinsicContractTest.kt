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
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustedIntrinsicContractTest {
    private val module = PlatformModuleId("std", "terminal")
    private val signature = CanonicalCallableSignature("fun(String):Unit")
    private val key = TrustedIntrinsicKey(module, callable("kotlin.io", "println"), signature)
    private val capability = PlatformCapabilityId("compukter", "stdio", 1)
    private val handler = handler(capability)

    @Test
    fun `exact external handler and executable target capability are accepted`() {
        val registry = TrustedIntrinsicRegistry.create(listOf(TrustedIntrinsicRegistration(key, handler)))

        TrustedIntrinsicContract.validate(listOf(declaration(external = true)), registry, setOf(capability))

        assertEquals(handler, registry.handlers.getValue(key))
    }

    @Test
    fun `external declaration without handler is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TrustedIntrinsicContract.validate(listOf(declaration(external = true)), TrustedIntrinsicRegistry.empty(), emptySet())
            }

        assertTrue(failure.message.orEmpty().contains("missing intrinsic handler"))
    }

    @Test
    fun `orphan and duplicate handlers are rejected`() {
        val orphan = TrustedIntrinsicRegistry.create(listOf(TrustedIntrinsicRegistration(key, handler)))
        assertFailsWith<IllegalArgumentException> {
            TrustedIntrinsicContract.validate(emptyList(), orphan, setOf(capability))
        }

        assertFailsWith<IllegalArgumentException> {
            TrustedIntrinsicRegistry.create(
                listOf(
                    TrustedIntrinsicRegistration(key, handler),
                    TrustedIntrinsicRegistration(key, handler),
                ),
            )
        }
    }

    @Test
    fun `signature mismatch and handler for ordinary declaration are rejected`() {
        val registry = TrustedIntrinsicRegistry.create(listOf(TrustedIntrinsicRegistration(key, handler)))
        val mismatched = declaration(external = true).copy(signature = "fun(Int):Unit")
        val mismatchFailure =
            assertFailsWith<IllegalArgumentException> {
                TrustedIntrinsicContract.validate(listOf(mismatched), registry, setOf(capability))
            }
        assertTrue(mismatchFailure.message.orEmpty().contains("signature mismatch"))

        val ordinaryFailure =
            assertFailsWith<IllegalArgumentException> {
                TrustedIntrinsicContract.validate(listOf(declaration(external = false)), registry, setOf(capability))
            }
        assertTrue(ordinaryFailure.message.orEmpty().contains("non-external"))
    }

    @Test
    fun `target cannot advertise module without executable capability`() {
        val registry = TrustedIntrinsicRegistry.create(listOf(TrustedIntrinsicRegistration(key, handler)))
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TrustedIntrinsicContract.validate(listOf(declaration(external = true)), registry, emptySet())
            }

        assertTrue(failure.message.orEmpty().contains("unavailable capability"))
    }

    @Test
    fun `native string and array externals have exact compiler primitive handlers`() {
        val builtins = PlatformModuleId("kotlin", "builtins")
        val expected =
            listOf(
                TrustedIntrinsicKey(
                    builtins,
                    CallableId(FqName("kotlin"), FqName("IntArray"), Name.special("<init>")),
                    CanonicalCallableSignature("constructor(Int)"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    CallableId(FqName("kotlin"), FqName("IntArray"), Name.identifier("get")),
                    CanonicalCallableSignature("fun(Int):Int"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    CallableId(FqName("kotlin"), FqName("IntArray"), Name.identifier("set")),
                    CanonicalCallableSignature("fun(Int,Int):Unit"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    CallableId(FqName("kotlin"), FqName("IntArray"), Name.identifier("size")),
                    CanonicalCallableSignature("val():Int"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    callable("kotlin", "intArrayOf"),
                    CanonicalCallableSignature("fun(Int):IntArray"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    CallableId(FqName("kotlin"), FqName("String"), Name.identifier("substring")),
                    CanonicalCallableSignature("fun(Int,Int):String"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    callable("kotlin.collections", "copyOfRange"),
                    CanonicalCallableSignature("fun(Array<T>.Int,Int):Array<T>"),
                ),
                TrustedIntrinsicKey(
                    builtins,
                    callable("kotlin.text", "concatToString"),
                    CanonicalCallableSignature("fun(CharArray.Int,Int):String"),
                ),
            )

        expected.forEach { key ->
            assertIs<CompilerPrimitiveHandler>(CanonicalTrustedIntrinsics.registry.handlers[key], key.toString())
        }
    }

    private fun declaration(external: Boolean): PlatformDeclaration =
        PlatformDeclaration(
            symbol = "kotlin.io.println",
            signature = signature.value,
            module = module,
            sourcePath = "Console.kt",
            startUtf16 = 0,
            endUtf16 = 7,
            trustedExternal = external,
        )

    private fun handler(capability: PlatformCapabilityId): TrustedIntrinsicHandler =
        object : TrustedIntrinsicHandler {
            override val requiredCapability: PlatformCapabilityId = capability
        }

    private fun callable(
        packageName: String,
        name: String,
    ): CallableId = CallableId(FqName(packageName), Name.identifier(name))
}
