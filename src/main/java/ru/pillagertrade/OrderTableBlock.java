package ru.pillagertrade;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class OrderTableBlock extends Block implements BlockEntityProvider {
    public OrderTableBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new OrderTableBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                               PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof OrderTableBlockEntity table) {
                serverPlayer.openHandledScreen(table);
            }
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (type == PillagerTradeMod.ORDER_TABLE_ENTITY) {
            return (tickWorld, pos, blockState, entity) -> ((OrderTableBlockEntity) entity).tick();
        }
        return null;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }
}