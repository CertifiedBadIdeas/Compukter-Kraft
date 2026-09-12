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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.util.Collections

data class PlatformCatalogEntry(
    val identity: ResolvedModule,
    val descriptor: PlatformModule,
)

data class ResolvedPlatformModule(
    val identity: ResolvedModule,
    val descriptor: PlatformModule,
    val direct: Boolean,
)

class ResolvedPlatformSelection internal constructor(
    modules: List<ResolvedPlatformModule>,
) {
    val modules: List<ResolvedPlatformModule> = Collections.unmodifiableList(modules.toList())
    val directModules: Set<ModuleId> =
        Collections.unmodifiableSet(
            this.modules
                .filter(ResolvedPlatformModule::direct)
                .mapTo(sortedSetOf(MODULE_ID_COMPARATOR)) { it.identity.id },
        )
}

class PlatformCatalog private constructor(
    val bundle: PlatformBundle,
    entries: List<PlatformCatalogEntry>,
) {
    val entries: List<PlatformCatalogEntry> =
        Collections.unmodifiableList(
            entries.sortedWith(
                compareBy(MODULE_ID_COMPARATOR) { it.identity.id },
            ),
        )
    private val byId = this.entries.associateBy { it.identity.id }
    private val graph = PlatformModuleGraph(bundle)

    fun find(id: ModuleId): PlatformCatalogEntry? = byId[id]

    fun require(id: ModuleId): PlatformCatalogEntry = requireNotNull(find(id)) { "platform module ${id.value} is unavailable" }

    fun resolve(requirements: Map<ModuleId, ApiMajor>): ResolvedPlatformSelection {
        requirements.forEach { (id, major) ->
            val available = require(id).identity
            require(available.major == major) {
                "platform module ${id.value} requires major ${major.value}, available ${available.major.value}"
            }
        }
        val roots = requirements.keys.mapTo(mutableSetOf(), ::platformId)
        val resolved = graph.resolve(roots)
        return ResolvedPlatformSelection(
            resolved.modules.map { descriptor ->
                val id = projectId(descriptor.id)
                val entry = require(id)
                ResolvedPlatformModule(entry.identity, entry.descriptor, id in requirements)
            },
        )
    }

    companion object {
        fun of(bundle: PlatformBundle): PlatformCatalog = PlatformCatalog(bundle, bundle.modules.map(::entry))

        fun forTarget(
            bundle: PlatformBundle,
            advertised: List<ResolvedModule>,
        ): PlatformCatalog {
            val local = of(bundle)
            val advertisedById = advertised.associateBy(ResolvedModule::id)
            require(advertisedById.size == advertised.size) { "target platform module IDs must be unique" }
            val entries =
                advertised.map { actual ->
                    val expected = local.require(actual.id)
                    require(expected.identity == actual) { "target platform module ${actual.id.value} identity does not match bundle" }
                    expected
                }
            val availableIds = advertisedById.keys
            entries.forEach { entry ->
                entry.descriptor.dependencies.forEach { dependency ->
                    if (dependency != bundle.builtins.id) {
                        val dependencyId = projectId(dependency)
                        require(dependencyId in availableIds) {
                            "target platform module ${entry.identity.id.value} has unavailable dependency ${dependencyId.value}"
                        }
                    }
                }
            }
            return PlatformCatalog(bundle, entries)
        }

        private fun entry(module: PlatformModule): PlatformCatalogEntry {
            val majorText = module.version.substringBefore('.')
            val major = majorText.toIntOrNull()
            require(major != null && major in 1..ApiMajor.MAXIMUM) {
                "platform module ${module.id} version must begin with a supported API major"
            }
            return PlatformCatalogEntry(
                identity =
                    ResolvedModule(
                        id = projectId(module.id),
                        major = ApiMajor(major),
                        version = module.version,
                        contentHash = Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                    ),
                descriptor = module,
            )
        }
    }
}

private val MODULE_ID_COMPARATOR = Comparator<ModuleId> { left, right -> compareUtf8(left.value, right.value) }

private fun compareUtf8(
    left: String,
    right: String,
): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    repeat(minOf(leftBytes.size, rightBytes.size)) { index ->
        val result = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (result != 0) return result
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

private fun platformId(id: ModuleId): PlatformModuleId = PlatformModuleId(id.provider, id.module)

private fun projectId(id: PlatformModuleId): ModuleId = ModuleId(id.namespace, id.name)
