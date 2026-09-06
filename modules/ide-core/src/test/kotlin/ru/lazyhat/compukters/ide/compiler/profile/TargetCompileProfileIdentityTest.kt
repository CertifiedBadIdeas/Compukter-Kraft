/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TargetCompileProfileIdentityTest {
    @Test
    fun `identity covers toolchain canonical modules and every worker limit`() {
        val base = profile()

        assertEquals(TargetCompileProfileIdentity.of(base), TargetCompileProfileIdentity.of(profile()))
        assertNotEquals(TargetCompileProfileIdentity.of(base), TargetCompileProfileIdentity.of(profile(payload = 2)))
        assertNotEquals(TargetCompileProfileIdentity.of(base), TargetCompileProfileIdentity.of(profile(moduleHash = 3)))
        assertNotEquals(
            TargetCompileProfileIdentity.of(base),
            TargetCompileProfileIdentity.of(profile(limits = WorkerLimits(artifactBytes = 1024))),
        )
    }

    private fun profile(
        payload: Int = 1,
        moduleHash: Int = 2,
        limits: WorkerLimits = WorkerLimits(),
    ) = TargetCompileProfile(
        ToolchainLockIdentity("2.4.0", "2.4", 1u, 2u, 1u, hash(payload), hash(7)),
        listOf(ResolvedModule(ModuleId("std", "terminal"), ApiMajor(2), "2.0.0", hash(moduleHash))),
        limits,
    )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
