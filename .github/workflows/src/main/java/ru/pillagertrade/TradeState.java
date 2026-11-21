package ru.pillagertrade;

import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.PersistentState;
import net.minecraft.registry.Registries;

import java.util.*;

public class TradeState extends PersistentState {
    private final Map<UUID, TraderData> traders = new HashMap<>();

    public static TradeState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TradeState::fromNbt, TradeState::new, "pillager_trade_state");
    }

    public TraderData getOrCreate(PillagerEntity entity) {
        return traders.computeIfAbsent(entity.getUuid(), id -> {
            TraderData data = new TraderData();
            data.specialization = entity.getRandom().nextInt(6);
            data.strength = entity.getRandom().nextInt(5) + 1;
            data.createdDay = entity.getWorld().getTime() / 24000L;
            markDirty();
            return data;
        });
    }

    public TraderData get(UUID id) { return traders.get(id); }

    public void onNewDay(ServerWorld world) {
        long day = world.getTime() / 24000L;
        for (TraderData data : traders.values()) {
            if (data.hired && day * 24000L >= data.hireUntil) data.hired = false;
            if (data.orderItem != null && day >= data.orderDueDay) data.orderReady = true;
            if (data.pendingRequest && day >= data.requestDeadline) {
                data.reputation = Math.max(0, data.reputation - 8);
                data.pendingRequest = false;
            }
            if (data.reputation >= 60 && !data.pendingRequest && world.getRandom().nextInt(100) < 3) {
                data.pendingRequest = true;
                data.requestDeadline = day + 10;
            }
        }
        markDirty();
    }

    public static List<Item> getStarterStock() {
        List<Item> result = new ArrayList<>();
        result.add(Items.BREAD);
        result.add(Items.TORCH);
        result.add(Items.OAK_PLANKS);
        result.add(Items.COBBLESTONE);
        result.add(Items.COAL);
        result.add(Items.IRON_INGOT);
        result.add(Items.LEATHER);
        result.add(Items.ARROW);
        return result;
    }

    public static int priceFor(Item item, TraderData data) {
        int base;
        if (item == Items.BREAD || item == Items.TORCH || item == Items.OAK_PLANKS || item == Items.COBBLESTONE) base = 2;
        else if (item == Items.COAL || item == Items.LEATHER || item == Items.ARROW) base = 4;
        else if (item == Items.IRON_INGOT) base = 8;
        else base = 10;
        int day = (int) Math.max(0, data.createdDay);
        return Math.max(1, base - data.reputation / 25 + data.strength);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, TraderData> entry : traders.entrySet()) {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("id", entry.getKey());
            TraderData d = entry.getValue();
            tag.putInt("reputation", d.reputation);
            tag.putInt("specialization", d.specialization);
            tag.putInt("strength", d.strength);
            tag.putLong("createdDay", d.createdDay);
            tag.putBoolean("hired", d.hired);
            tag.putLong("hireUntil", d.hireUntil);
            tag.putString("orderItem", d.orderItem == null ? "" : d.orderItem);
            tag.putLong("orderDueDay", d.orderDueDay);
            tag.putBoolean("orderReady", d.orderReady);
            tag.putBoolean("pendingRequest", d.pendingRequest);
            tag.putLong("requestDeadline", d.requestDeadline);
            list.add(tag);
        }
        nbt.put("traders", list);
        return nbt;
    }

    public static TradeState fromNbt(NbtCompound nbt) {
        TradeState state = new TradeState();
        NbtList list = nbt.getList("traders", 10);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            if (!tag.containsUuid("id")) continue;
            TraderData d = new TraderData();
            d.reputation = tag.getInt("reputation");
            d.specialization = tag.getInt("specialization");
            d.strength = tag.getInt("strength");
            d.createdDay = tag.getLong("createdDay");
            d.hired = tag.getBoolean("hired");
            d.hireUntil = tag.getLong("hireUntil");
            d.orderItem = tag.getString("orderItem").isEmpty() ? null : tag.getString("orderItem");
            d.orderDueDay = tag.getLong("orderDueDay");
            d.orderReady = tag.getBoolean("orderReady");
            d.pendingRequest = tag.getBoolean("pendingRequest");
            d.requestDeadline = tag.getLong("requestDeadline");
            state.traders.put(tag.getUuid("id"), d);
        }
        return state;
    }

    public static class TraderData {
        public int reputation = 0;
        public int specialization = 0;
        public int strength = 1;
        public long createdDay = 0;
        public boolean hired = false;
        public long hireUntil = 0;
        public String orderItem;
        public long orderDueDay;
        public boolean orderReady;
        public boolean pendingRequest;
        public long requestDeadline;
    }
}
