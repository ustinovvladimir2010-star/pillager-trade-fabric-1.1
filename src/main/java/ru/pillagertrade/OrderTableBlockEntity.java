package ru.pillagertrade;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OrderTableBlockEntity extends BlockEntity implements NamedScreenHandlerFactory {
    private final SimpleInventory inventory = new SimpleInventory(10);
    private UUID owner;
    private UUID trader;
    private String itemId = "";
    private long dueTime;
    private int emeraldCost;
    private boolean active;
    private boolean ready;
    private boolean failed;

    public OrderTableBlockEntity(BlockPos pos, BlockState state) {
        super(PillagerTradeMod.ORDER_TABLE_ENTITY, pos, state);
    }

    public SimpleInventory getInventory() {
        return inventory;
    }

    public boolean hasActiveOrder() {
        return active;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getOrderItemId() {
        return itemId;
    }

    public int getEmeraldCost() {
        return emeraldCost;
    }

    public UUID getSelectedTrader() {
        return trader;
    }

    public boolean startOrder(ServerPlayerEntity player, UUID traderId) {
        if (!(world instanceof ServerWorld serverWorld) || active || ready) return false;
        if (inventory.getStack(0).isEmpty()) return false;
        if (!(serverWorld.getEntity(traderId) instanceof PillagerEntity pillager) || !pillager.isAlive()) return false;
        if (player.squaredDistanceTo(pillager) > 256.0 || player.squaredDistanceTo(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return false;

        ItemStack sample = inventory.getStack(0);
        Item item = sample.getItem();
        int cost = TradeState.orderPriceFor(item) * 8;
        if (!PillagerTradeMod.removeEmeralds(player, cost)) {
            player.sendMessage(Text.literal("Недостаточно изумрудов. Заказ стоит " + cost + " изумрудов."), true);
            PillagerTradeMod.syncEmeraldBalance(player);
            return false;
        }

        owner = player.getUuid();
        trader = traderId;
        itemId = Registries.ITEM.getId(item).toString();
        emeraldCost = cost;
        long daysToReturn = 5 + serverWorld.getRandom().nextInt(16);
        dueTime = serverWorld.getTime() + daysToReturn * 24000L;
        active = true;
        ready = false;
        failed = false;
        inventory.setStack(0, ItemStack.EMPTY);
        markDirty();
        player.sendMessage(Text.literal("Поборник отправился за 8 предметами. Ожидайте от 5 до 20 игровых дней."), false);
        PillagerTradeMod.syncEmeraldBalance(player);
        return true;
    }

    public void tick() {
        if (!(world instanceof ServerWorld serverWorld) || !active || serverWorld.getTime() < dueTime) return;
        active = false;
        TradeState state = TradeState.get(serverWorld);
        TradeState.TraderData data = state.get(trader);
        double chance = data == null ? 0.60 : Math.min(0.90, 0.60 + data.reputation * 0.002 + data.strength * 0.02);
        if (serverWorld.getRandom().nextDouble() < chance) {
            Item item = Registries.ITEM.getOrEmpty(new net.minecraft.util.Identifier(itemId))
                    .orElse(net.minecraft.item.Items.AIR);
            if (item != net.minecraft.item.Items.AIR) {
                ItemStack reward = new ItemStack(item, Math.min(8, item.getMaxCount()));
                if (!inventory.getStack(1).isEmpty() && ItemStack.canCombine(inventory.getStack(1), reward)) {
                    inventory.getStack(1).increment(reward.getCount());
                } else {
                    inventory.setStack(1, reward);
                }
                ready = true;
                if (data != null) {
                    data.reputation = Math.min(100, data.reputation + 3);
                    state.markDirty();
                }
            } else {
                failed = true;
            }
        } else {
            failed = true;
        }
        ServerPlayerEntity player = serverWorld.getServer().getPlayerManager().getPlayer(owner);
        if (player != null) {
            player.sendMessage(Text.literal(ready
                    ? "Поборник вернулся! Заказ доставлен в стол заказов."
                    : "Поборник не смог выполнить заказ. Оплата не возвращается."), false);
        }
        markDirty();
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("block.pillager_trade.order_table");
    }

    @Override
    public ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory playerInventory, PlayerEntity player) {
        List<OrderTableScreenHandler.TraderOption> options = new ArrayList<>();
        if (world instanceof ServerWorld serverWorld) {
            Box area = new Box(pos).expand(16);
            for (PillagerEntity pillager : serverWorld.getEntitiesByClass(PillagerEntity.class, area, PillagerEntity::isAlive)) {
                TradeState.TraderData data = TradeState.get(serverWorld).getOrCreate(pillager);
                options.add(new OrderTableScreenHandler.TraderOption(pillager.getUuid(), pillager.getName().getString(),
                        data.reputation, data.strength));
            }
        }
        return new OrderTableScreenHandler(syncId, playerInventory, this, options);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
        List<PillagerEntity> nearby = world instanceof ServerWorld serverWorld
                ? serverWorld.getEntitiesByClass(PillagerEntity.class, new Box(pos).expand(16), PillagerEntity::isAlive)
                : List.of();
        buf.writeVarInt(nearby.size());
        for (PillagerEntity pillager : nearby) {
            TradeState.TraderData data = TradeState.get((ServerWorld) world).getOrCreate(pillager);
            buf.writeUuid(pillager.getUuid());
            buf.writeString(pillager.getName().getString());
            buf.writeInt(data.reputation);
            buf.writeInt(data.strength);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        Inventories.readNbt(nbt, inventory.heldStacks);
        active = nbt.getBoolean("active");
        ready = nbt.getBoolean("ready");
        failed = nbt.getBoolean("failed");
        itemId = nbt.getString("itemId");
        dueTime = nbt.getLong("dueTime");
        emeraldCost = nbt.getInt("emeraldCost");
        if (nbt.containsUuid("owner")) owner = nbt.getUuid("owner");
        if (nbt.containsUuid("trader")) trader = nbt.getUuid("trader");
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        Inventories.writeNbt(nbt, inventory.heldStacks);
        nbt.putBoolean("active", active);
        nbt.putBoolean("ready", ready);
        nbt.putBoolean("failed", failed);
        nbt.putString("itemId", itemId);
        nbt.putLong("dueTime", dueTime);
        nbt.putInt("emeraldCost", emeraldCost);
        if (owner != null) nbt.putUuid("owner", owner);
        if (trader != null) nbt.putUuid("trader", trader);
    }
}