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

package ru.lazyhat.compukters.platform.bundle

class ResolvedPlatformModules internal constructor(
    directRoots: Set<PlatformModuleId>,
    modules: List<PlatformModule>,
) {
    val directRoots: Set<PlatformModuleId> = directRoots.toSortedSet()
    val modules: List<PlatformModule> = modules.toList()

    fun isDirect(module: PlatformModuleId): Boolean = module in directRoots
}

class PlatformModuleGraph(
    private val bundle: PlatformBundle,
) {
    private val modules = bundle.modules.associateBy(PlatformModule::id)

    init {
        require(modules.size == bundle.modules.size) { "platform bundle contains duplicate module ids" }
        bundle.modules.forEach { module ->
            module.dependencies.forEach { dependency ->
                require(dependency == bundle.builtins.id || dependency in modules) {
                    "platform module ${module.id} has unknown dependency $dependency"
                }
            }
        }
        validateAcyclic()
    }

    fun resolve(roots: Set<PlatformModuleId>): ResolvedPlatformModules {
        val directRoots = roots.toSortedSet()
        directRoots.forEach { root ->
            require(root in modules) { "unknown module root: $root" }
        }
        val resolved = mutableListOf<PlatformModule>()
        val visited = mutableSetOf<PlatformModuleId>()

        fun visit(id: PlatformModuleId) {
            if (!visited.add(id)) return
            val module = modules.getValue(id)
            module.dependencies.sorted().forEach { dependency ->
                if (dependency != bundle.builtins.id) visit(dependency)
            }
            resolved += module
        }
        directRoots.forEach(::visit)
        return ResolvedPlatformModules(directRoots, resolved)
    }

    private fun validateAcyclic() {
        val visited = mutableSetOf<PlatformModuleId>()
        val active = mutableListOf<PlatformModuleId>()
        val activeIndexes = mutableMapOf<PlatformModuleId, Int>()

        fun visit(id: PlatformModuleId) {
            activeIndexes[id]?.let { cycleStart ->
                val cycle = active.subList(cycleStart, active.size) + id
                throw IllegalArgumentException("platform module dependency cycle: ${cycle.joinToString(" -> ")}")
            }
            if (!visited.add(id)) return
            activeIndexes[id] = active.size
            active += id
            modules.getValue(id).dependencies.sorted().forEach { dependency ->
                if (dependency != bundle.builtins.id) visit(dependency)
            }
            active.removeAt(active.lastIndex)
            activeIndexes.remove(id)
        }

        modules.keys.sorted().forEach(::visit)
    }
}
