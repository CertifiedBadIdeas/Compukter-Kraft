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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import java.util.function.Supplier

class ComputerBlock(
    properties: BlockBehaviour.Properties,
    private val factory: (BlockPos, BlockState) -> ComputerBlockEntity,
    private val blockEntityType: Supplier<out BlockEntityType<out ComputerBlockEntity>>,
    private val terminalOpener: (ServerPlayer, ComputerBlockEntity) -> Unit,
) : Block(properties),
    EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, COMPUTER_DEFAULT_FACING))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, placementFacing(context.horizontalDirection))

    override fun rotate(
        blockState: BlockState,
        rotation: Rotation,
    ): BlockState = blockState.setValue(FACING, rotateComputerFacing(blockState.getValue(FACING), rotation))

    override fun mirror(
        blockState: BlockState,
        mirror: Mirror,
    ): BlockState = blockState.setValue(FACING, mirrorComputerFacing(blockState.getValue(FACING), mirror))

    override fun newBlockEntity(
        position: BlockPos,
        blockState: BlockState,
    ): ComputerBlockEntity = factory(position, blockState)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        blockState: BlockState,
        actualType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = computerTickerFor(level.isClientSide, actualType, blockEntityType.get())

    override fun useWithoutItem(
        blockState: BlockState,
        level: Level,
        position: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val entity = level.getBlockEntity(position) as? ComputerBlockEntity ?: return InteractionResult.PASS
        terminalOpener(serverPlayer, entity)
        return InteractionResult.SUCCESS_SERVER
    }

    override fun playerWillDestroy(
        level: Level,
        position: BlockPos,
        blockState: BlockState,
        player: Player,
    ): BlockState {
        if (!level.isClientSide) {
            (level.getBlockEntity(position) as? ComputerBlockEntity)?.destroyFileSystem()
        }
        return super.playerWillDestroy(level, position, blockState, player)
    }

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        direction: Direction,
    ): Int =
        ((level.getBlockEntity(position) as? ComputerBlockEntity)?.redstoneOutput(direction.opposite) ?: 0) and
            RedstoneWire.SIGNAL_MASK

    override fun getDirectSignal(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        direction: Direction,
    ): Int {
        val output = (level.getBlockEntity(position) as? ComputerBlockEntity)?.redstoneOutput(direction.opposite) ?: 0
        return if (output and RedstoneWire.DIRECT_MASK != 0) {
            output and RedstoneWire.SIGNAL_MASK
        } else {
            0
        }
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        position: BlockPos,
        block: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, position, block, orientation, movedByPiston)
        (level.getBlockEntity(position) as? ComputerBlockEntity)
            ?.markRedstoneInputDirty(orientation?.front?.opposite)
    }

    override fun onPlace(
        state: BlockState,
        level: Level,
        position: BlockPos,
        oldState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, position, oldState, movedByPiston)
        if (!level.isClientSide && oldState.block === this && oldState.getValue(FACING) != state.getValue(FACING)) {
            (level.getBlockEntity(position) as? ComputerBlockEntity)?.markRedstoneInputDirty()
            Direction.entries.forEach { direction ->
                level.neighborChanged(position.relative(direction), this, null)
            }
        }
    }

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }
}

internal fun placementFacing(playerFacing: Direction): Direction {
    require(playerFacing.axis.isHorizontal) { "computer placement facing must be horizontal" }
    return playerFacing.opposite
}

internal val COMPUTER_DEFAULT_FACING: Direction = Direction.NORTH

internal fun rotateComputerFacing(
    facing: Direction,
    rotation: Rotation,
): Direction = rotation.rotate(facing)

internal fun mirrorComputerFacing(
    facing: Direction,
    mirror: Mirror,
): Direction = mirror.getRotation(facing).rotate(facing)

internal fun <T : BlockEntity> computerTickerFor(
    isClientSide: Boolean,
    actualType: BlockEntityType<T>,
    expectedType: BlockEntityType<out ComputerBlockEntity>,
): BlockEntityTicker<T>? {
    if (isClientSide || actualType !== expectedType) return null
    return BlockEntityTicker { _, _, _, entity ->
        tickComputerEntity(entity as ComputerBlockEntity)
    }
}

internal fun tickComputerEntity(entity: ComputerBlockEntity) = entity.serverTick()
