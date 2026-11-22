package ru.pillagertrade;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
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

public class OrderTableBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
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
        inventory.addListener(changed -> markDirty());
    }

    public SimpleInventory getInventory() { return inventory; }
    public boolean hasActiveOrder() { return active; }
    public boolean isReady() { return ready; }
    public boolean isFailed() { return failed; }
    public String getOrderItemId() { return itemId; }
    public int getEmeraldCost() { return emeraldCost; }

    public boolean startOrder(ServerPlayerEntity player, UUID traderId) {
        if (!(world instanceof ServerWorld serverWorld) || active || !inventory.getStack(1).isEmpty()) return false;
        ItemStack sample = inventory.getStack(0);
        if (sample.isEmpty()) return false;
        if (!(serverWorld.getEntity(traderId) instanceof PillagerEntity pillager) || !pillager.isAlive()) return false;
        if (player.squaredDistanceTo(pillager) > 256.0 || player.squaredDistanceTo(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return false;

        int requestedAmount = Math.min(8, sample.getItem().getMaxCount());
        int cost = TradeState.orderPriceFor(sample.getItem()) * requestedAmount;
        if (!PillagerTradeMod.removeEmeralds(player, cost)) {
            player.sendMessage(Text.literal("Недостаточно изумрудов. Заказ стоит " + cost + " изумрудов."), true);
            PillagerTradeMod.syncEmeraldBalance(player);
            return false;
        }

        owner = player.getUuid();
        trader = traderId;
        itemId = Registries.ITEM.getId(sample.getItem()).toString();
        emeraldCost = cost;
        dueTime = serverWorld.getTime() + (5 + serverWorld.getRandom().nextInt(16)) * 24000L;
        active = true;
        ready = false;
        failed = false;
        inventory.setStack(0, ItemStack.EMPTY);
        markDirty();
        player.sendMessage(Text.literal("Поборник отправился за предметами. Количество: " + requestedAmount + ". Ожидайте от 5 до 20 игровых дней."), false);
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
            Item item;
            try {
                item = Registries.ITEM.getOrEmpty(new net.minecraft.util.Identifier(itemId))
                        .orElse(net.minecraft.item.Items.AIR);
            } catch (IllegalArgumentException exception) {
                item = net.minecraft.item.Items.AIR;
            }
            if (item != net.minecraft.item.Items.AIR) {
                inventory.setStack(1, new ItemStack(item, Math.min(8, item.getMaxCount())));
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
        ServerPlayerEntity player = owner == null ? null : serverWorld.getServer().getPlayerManager().getPlayer(owner);
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
        return new OrderTableScreenHandler(syncId, playerInventory, this, getNearbyTraders());
    }

    private List<OrderTableScreenHandler.TraderOption> getNearbyTraders() {
        List<OrderTableScreenHandler.TraderOption> options = new ArrayList<>();
        if (world instanceof ServerWorld serverWorld) {
            for (PillagerEntity pillager : serverWorld.getEntitiesByClass(PillagerEntity.class,
                    new Box(pos).expand(16), PillagerEntity::isAlive)) {
                TradeState.TraderData data = TradeState.get(serverWorld).getOrCreate(pillager);
                options.add(new OrderTableScreenHandler.TraderOption(pillager.getUuid(),
                        pillager.getName().getString(), data.reputation, data.strength));
            }
        }
        return options;
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
        List<OrderTableScreenHandler.TraderOption> options = getNearbyTraders();
        buf.writeVarInt(options.size());
        for (OrderTableScreenHandler.TraderOption option : options) {
            buf.writeUuid(option.id());
            buf.writeString(option.name());
            buf.writeInt(option.reputation());
            buf.writeInt(option.strength());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        NbtList stacks = nbt.getList("Items", 10);
        for (int i = 0; i < stacks.size(); i++) {
            NbtCompound stackTag = stacks.getCompound(i);
            int slot = stackTag.getByte("Slot") & 255;
            if (slot < inventory.size()) inventory.setStack(slot, ItemStack.fromNbt(stackTag));
        }
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
        NbtList stacks = new NbtList();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                NbtCompound stackTag = new NbtCompound();
                stackTag.putByte("Slot", (byte) i);
                stack.writeNbt(stackTag);
                stacks.add(stackTag);
            }
        }
        nbt.put("Items", stacks);
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