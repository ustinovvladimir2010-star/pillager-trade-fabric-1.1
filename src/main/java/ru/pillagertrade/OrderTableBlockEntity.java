package ru.pillagertrade;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Items;
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
    private long startTime;
    private boolean summonOrder;
    private int summonedCount;
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
    public long getStartTime() { return startTime; }
    public long getDueTime() { return dueTime; }
    public boolean isSummonOrder() { return summonOrder; }
    public int getProgress(ServerWorld world) {
        if (!active) return ready ? 100 : 0;
        long duration = Math.max(1L, dueTime - startTime);
        return (int) Math.max(0, Math.min(100, (world.getTime() - startTime) * 100 / duration));
    }

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
        startTime = serverWorld.getTime();
        dueTime = startTime + (5 + serverWorld.getRandom().nextInt(16)) * 24000L;
        summonOrder = false;
        active = true;
        ready = false;
        failed = false;
        inventory.setStack(0, ItemStack.EMPTY);
        markDirty();
        player.sendMessage(Text.literal("Поборник отправился за предметами. Количество: " + requestedAmount + ". Ожидайте от 5 до 20 игровых дней."), false);
        PillagerTradeMod.syncEmeraldBalance(player);
        return true;
    }


    private static double deliveryChance(Item item, int reputation) {
        double chance = 0.78 + Math.min(0.17, reputation * 0.0017);
        if (item == null || item == Items.AIR) return 0.05;
        net.minecraft.util.Rarity rarity = item.getDefaultStack().getRarity();
        if (rarity == net.minecraft.util.Rarity.UNCOMMON) chance -= 0.10;
        else if (rarity == net.minecraft.util.Rarity.RARE) chance -= 0.22;
        else if (rarity == net.minecraft.util.Rarity.EPIC) chance -= 0.35;
        if (item.getMaxDamage() > 0) chance -= 0.05;
        return Math.max(0.10, Math.min(0.95, chance));
    }

    public boolean startSummonOrder(ServerPlayerEntity player) {
        if (!(world instanceof ServerWorld serverWorld) || active || !inventory.getStack(1).isEmpty()) return false;
        if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return false;
        int cost = 24;
        if (!PillagerTradeMod.removeEmeralds(player, cost)) {
            player.sendMessage(Text.literal("Для призыва подкрепления нужно 24 изумруда."), true);
            PillagerTradeMod.syncEmeraldBalance(player);
            return false;
        }
        owner = player.getUuid();
        trader = null;
        itemId = "";
        emeraldCost = cost;
        startTime = serverWorld.getTime();
        dueTime = startTime + (2 + serverWorld.getRandom().nextInt(2)) * 24000L;
        active = true;
        ready = false;
        failed = false;
        summonOrder = true;
        summonedCount = 1 + serverWorld.getRandom().nextInt(3);
        player.sendMessage(Text.literal("Подкрепление в пути: " + summonedCount + " разбойник(а). Прибытие через 2–3 игровых дня."), false);
        PillagerTradeMod.syncEmeraldBalance(player);
        markDirty();
        return true;
    }

    private void completeSummonOrder(ServerWorld world) {
        ServerPlayerEntity player = owner == null ? null : world.getServer().getPlayerManager().getPlayer(owner);
        if (player == null || !player.isAlive()) {
            failed = true;
            active = false;
            return;
        }
        int spawned = 0;
        for (int i = 0; i < summonedCount; i++) {
            boolean melee = world.getRandom().nextBoolean();
            net.minecraft.entity.mob.MobEntity raider = melee
                    ? EntityType.VINDICATOR.create(world) : EntityType.PILLAGER.create(world);
            if (raider == null) continue;
            double angle = (Math.PI * 2.0 * i / Math.max(1, summonedCount)) + world.getRandom().nextDouble() * 0.5;
            double x = player.getX() + Math.cos(angle) * 2.5;
            double z = player.getZ() + Math.sin(angle) * 2.5;
            raider.refreshPositionAndAngles(x, player.getY(), z, world.getRandom().nextFloat() * 360.0F, 0.0F);
            int level = 1 + world.getRandom().nextInt(5);
            if (raider instanceof PillagerEntity pillager) {
                pillager.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            } else if (raider instanceof VindicatorEntity vindicator) {
                vindicator.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            }
            if (raider.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null) raider.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(24.0 + level * 4.0);
            if (raider.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE) != null) raider.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(4.0 + level * 1.25);
            raider.setHealth((float) (24.0 + level * 4.0));
            raider.setCustomName(Text.literal("Разбойник • ур. " + level));
            raider.setCustomNameVisible(true);
            world.spawnEntity(raider);
            spawned++;
        }
        active = false;
        ready = spawned > 0;
        failed = spawned == 0;
        player.sendMessage(Text.literal(ready
                ? "Подкрепление прибыло! Разбойников: " + spawned + ". Их уровень указан над головами."
                : "Не удалось призвать подкрепление."), false);
    }

    public void tick() {
        if (!(world instanceof ServerWorld serverWorld) || !active) return;
        if (serverWorld.getTime() % 20L == 0L) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        if (serverWorld.getTime() < dueTime) return;
        active = false;
        TradeState state = TradeState.get(serverWorld);
        TradeState.TraderData data = state.get(trader);
        double chance = data == null ? 0.60 : deliveryChance(Registries.ITEM.getOrEmpty(new net.minecraft.util.Identifier(itemId)).orElse(Items.AIR), data.reputation);
        if (summonOrder) {
            completeSummonOrder(serverWorld);
            markDirty();
            return;
        }
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
        startTime = nbt.getLong("startTime");
        summonOrder = nbt.getBoolean("summonOrder");
        summonedCount = nbt.getInt("summonedCount");
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
        nbt.putLong("startTime", startTime);
        nbt.putBoolean("summonOrder", summonOrder);
        nbt.putInt("summonedCount", summonedCount);
        nbt.putInt("emeraldCost", emeraldCost);
        if (owner != null) nbt.putUuid("owner", owner);
        if (trader != null) nbt.putUuid("trader", trader);
    }
}