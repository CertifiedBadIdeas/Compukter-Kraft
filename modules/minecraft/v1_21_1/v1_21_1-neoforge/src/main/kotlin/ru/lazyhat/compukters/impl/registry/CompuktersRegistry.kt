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

@file:Suppress("ktlint:standard:filename")

package ru.lazyhat.compukters.impl.registry

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.computer.NeoForgeComputerBlockEntity
import ru.lazyhat.compukters.impl.terminal.TerminalNetwork
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import java.util.function.Supplier

object CompuktersRegistry {
    private val blocks = DeferredRegister.createBlocks(MOD_ID)
    private val items = DeferredRegister.createItems(MOD_ID)
    private val blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID)
    private val creativeTabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)

    val COMPUTER: DeferredBlock<ComputerBlock> =
        blocks.register(
            "compukter",
            Supplier {
                ComputerBlock(
                    BlockBehaviour.Properties.of().strength(2.0f),
                    ::NeoForgeComputerBlockEntity,
                    Supplier { COMPUTER_BLOCK_ENTITY.get() },
                    TerminalNetwork::open,
                )
            },
        )

    val COMPUTER_ITEM: DeferredItem<BlockItem> = items.registerSimpleBlockItem(COMPUTER)

    val COMPUKTERS_TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> =
        creativeTabs.register(
            "compukters",
            Supplier {
                CreativeModeTab
                    .builder()
                    .title(Component.translatable("itemGroup.compukters"))
                    .icon { ItemStack(COMPUTER_ITEM.get()) }
                    .displayItems { _, output -> output.accept(COMPUTER_ITEM.get()) }
                    .build()
            },
        )

    val COMPUTER_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<NeoForgeComputerBlockEntity>> =
        blockEntities.register(
            "compukter",
            Supplier {
                BlockEntityType.Builder.of(::NeoForgeComputerBlockEntity, COMPUTER.get()).build(null)
            },
        )

    fun register(eventBus: IEventBus) {
        blocks.register(eventBus)
        items.register(eventBus)
        blockEntities.register(eventBus)
        creativeTabs.register(eventBus)
    }
}
