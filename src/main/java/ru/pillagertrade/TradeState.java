package ru.pillagertrade;

import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.world.PersistentState;
import net.minecraft.registry.Registries;

import java.util.*;

public class TradeState extends PersistentState {
    private static final int STOCK_SIZE = 8;
    private static final int[] STAGE_DAYS = {0, 10, 25, 45, 70};
    private static final int[] STAGE_REPUTATION = {0, 15, 30, 50, 70};

    private final Map<UUID, TraderData> traders = new HashMap<>();

    public static TradeState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TradeState::fromNbt, TradeState::new, "pillager_trade_state");
    }

    public TraderData getOrCreate(PillagerEntity entity) {
        return traders.computeIfAbsent(entity.getUuid(), id -> {
            TraderData data = new TraderData();
            data.specialization = entity.getRandom().nextInt(7);
            data.strength = entity.getRandom().nextInt(5) + 1;
            data.createdDay = entity.getWorld().getTime() / 24000L;
            markDirty();
            return data;
        });
    }

    public TraderData get(UUID id) {
        return traders.get(id);
    }

    public static int getStage(long worldDay, int reputation) {
        int stage = 1;
        for (int i = 1; i < STAGE_DAYS.length; i++) {
            if (worldDay >= STAGE_DAYS[i] && reputation >= STAGE_REPUTATION[i]) {
                stage = i + 1;
            }
        }
        return stage;
    }

    public static int daysForNextStage(int stage) {
        return stage >= 5 ? STAGE_DAYS[4] : STAGE_DAYS[Math.max(0, stage)];
    }

    public static int reputationForNextStage(int stage) {
        return stage >= 5 ? STAGE_REPUTATION[4] : STAGE_REPUTATION[Math.max(0, stage)];
    }

    /**
     * Returns this trader's saved assortment for the current stage.
     * Each trader receives a different assortment, but it remains stable after relogging.
     */
    public List<Item> getStock(TraderData data, int stage) {
        List<String> savedIds = data.stockByStage.get(stage);
        if (savedIds == null) {
            savedIds = generateStockIds(data, stage);
            data.stockByStage.put(stage, savedIds);
            markDirty();
        }

        List<Item> result = new ArrayList<>();
        for (String value : savedIds) {
            try {
                Item item = Registries.ITEM.getOrEmpty(new Identifier(value)).orElse(Items.AIR);
                if (item != Items.AIR) result.add(item);
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid or outdated item identifiers from old saves.
            }
        }
        return result;
    }

    private List<String> generateStockIds(TraderData data, int stage) {
        List<Item> candidates = new ArrayList<>();
        List<Item> moddedCandidates = new ArrayList<>();

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR || item instanceof SpawnEggItem) continue;
            if (getItemTier(item) > stage) continue;
            if (!matchesSpecialization(item, data.specialization)) continue;
            candidates.add(item);
            if (!Registries.ITEM.getId(item).getNamespace().equals("minecraft")) {
                moddedCandidates.add(item);
            }
        }

        long seed = data.seed ^ (0x9E3779B97F4A7C15L * stage);
        Random random = new Random(seed);
        Collections.shuffle(candidates, random);
        Collections.shuffle(moddedCandidates, random);

        // Keep the assortment focused, but guarantee a modded item when one exists
        // for this specialization and progression stage.
        LinkedHashSet<Item> selected = new LinkedHashSet<>();
        if (!moddedCandidates.isEmpty()) selected.add(moddedCandidates.get(0));

        // Prefer items belonging to the current tier, then fill with earlier staples.
        candidates.sort((a, b) -> Integer.compare(getItemTier(b), getItemTier(a)));
        for (Item item : candidates) {
            if (selected.size() >= STOCK_SIZE) break;
            selected.add(item);
        }

        // Some modpacks have very few items in a narrow category. Fill remaining
        // slots with sensible vanilla staples rather than unrelated modded clutter.
        if (selected.size() < STOCK_SIZE) {
            List<Item> fallback = new ArrayList<>();
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR || item instanceof SpawnEggItem || selected.contains(item)) continue;
                if (!Registries.ITEM.getId(item).getNamespace().equals("minecraft")) continue;
                if (getItemTier(item) <= stage && isUsefulFallback(item)) fallback.add(item);
            }
            Collections.shuffle(fallback, random);
            for (Item item : fallback) {
                if (selected.size() >= STOCK_SIZE) break;
                selected.add(item);
            }
        }

        List<String> ids = new ArrayList<>();
        for (Item item : selected) ids.add(Registries.ITEM.getId(item).toString());
        return ids;
    }

    private static boolean matchesSpecialization(Item item, int specialization) {
        String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);
        String name = item.getName().getString().toLowerCase(Locale.ROOT);

        // Construction materials are classified first so waxed copper blocks,
        // concrete and decorative blocks never leak into combat/alchemy stock.
        boolean building = containsAny(path, "block", "concrete", "terracotta", "brick",
                "planks", "log", "wood", "stone", "copper", "glass", "wool", "slab",
                "stairs", "wall", "fence", "door", "trapdoor", "tile", "marble",
                "limestone", "basalt", "deepslate", "sandstone", "quartz_block",
                "clay", "scaffolding", "pane", "carpet")
                || containsAny(name, "concrete", "waxed", "building", "bricks");
        boolean food = containsAny(path, "bread", "food", "stew", "meat", "fish",
                "apple", "carrot", "potato", "wheat", "seed", "cookie", "berry",
                "berries", "melon", "pumpkin", "beetroot", "sugar", "honey");
        boolean combat = containsAny(path, "sword", "axe", "bow", "crossbow", "shield",
                "helmet", "chestplate", "leggings", "boots", "armor", "arrow",
                "weapon", "trident", "mace");
        boolean mining = containsAny(path, "ore", "ingot", "raw_", "nugget", "gem",
                "diamond", "emerald", "coal", "iron", "gold", "lapis", "quartz",
                "amethyst", "copper_ingot", "ancient_debris");
        boolean alchemy = containsAny(path, "potion", "bottle", "ingredient", "herb",
                "brew", "wart", "dust", "powder", "flower", "sugar", "fermented",
                "phantom_membrane", "blaze_rod", "magma_cream", "ghast_tear",
                "spider_eye", "slime_ball");
        boolean machinery = containsAny(path, "redstone", "piston", "repeater",
                "comparator", "hopper", "observer", "dispenser", "dropper", "rail",
                "lever", "button", "pressure_plate", "gear", "machine", "circuit",
                "copper_bulb", "lightning_rod", "target");

        return switch (specialization) {
            case 0 -> food || containsAny(path, "torch", "lantern", "bed", "bucket", "campfire");
            case 1 -> combat;
            case 2 -> mining;
            case 3 -> alchemy;
            case 4 -> machinery;
            case 6 -> {
                boolean modded = !Registries.ITEM.getId(item).getNamespace().equals("minecraft");
                Rarity rarity = item.getDefaultStack().getRarity();
                yield modded || rarity == Rarity.UNCOMMON || rarity == Rarity.RARE || rarity == Rarity.EPIC;
            }
            case 5 -> building;
            default -> false;
        };
    }

    private static boolean isUsefulFallback(Item item) {
        String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);
        return containsAny(path, "bread", "torch", "planks", "cobblestone", "stone",
                "stick", "coal", "iron_ingot", "arrow", "leather", "string", "glass");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    /**
     * A registry-wide heuristic because Minecraft does not expose a universal progression tier
     * for arbitrary items supplied by other mods.
     */
    private static int getItemTier(Item item) {
        int tier = 1;
        // Do not penalize an item merely because it comes from a mod:
        // modded items must be able to appear from the first trade stage.
        if (item.getMaxDamage() > 0) tier++;
        if (item.getMaxDamage() > 500) tier++;
        if (item.getMaxCount() <= 16) tier++;

        Rarity rarity = item.getDefaultStack().getRarity();
        if (rarity == Rarity.UNCOMMON) tier++;
        else if (rarity == Rarity.RARE) tier += 2;
        else if (rarity == Rarity.EPIC) tier += 3;

        return Math.min(5, tier);
    }

    public void onNewDay(ServerWorld world) {
        long day = world.getTime() / 24000L;
        for (TraderData data : traders.values()) {
            if (data.hired && world.getTime() >= data.hireUntil) data.hired = false;
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

    public static int priceFor(Item item, TraderData data) {
        int base = 3;
        if (item.getMaxDamage() > 0) base += 5;
        if (item.getMaxDamage() > 500) base += 5;
        if (item.getMaxCount() <= 16) base += 3;

        Rarity rarity = item.getDefaultStack().getRarity();
        if (rarity == Rarity.UNCOMMON) base += 3;
        else if (rarity == Rarity.RARE) base += 7;
        else if (rarity == Rarity.EPIC) base += 12;

        if (item == Items.BREAD || item == Items.TORCH || item == Items.OAK_PLANKS || item == Items.COBBLESTONE) {
            base = 2;
        } else if (item == Items.COAL || item == Items.LEATHER || item == Items.ARROW) {
            base = 4;
        } else if (item == Items.IRON_INGOT) {
            base = 8;
        }

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
            tag.putLong("seed", d.seed);
            tag.putBoolean("hired", d.hired);
            tag.putLong("hireUntil", d.hireUntil);
            tag.putString("orderItem", d.orderItem == null ? "" : d.orderItem);
            tag.putLong("orderDueDay", d.orderDueDay);
            tag.putBoolean("orderReady", d.orderReady);
            tag.putBoolean("pendingRequest", d.pendingRequest);
            tag.putLong("requestDeadline", d.requestDeadline);

            NbtList stockStages = new NbtList();
            for (Map.Entry<Integer, List<String>> stockEntry : d.stockByStage.entrySet()) {
                NbtCompound stageTag = new NbtCompound();
                stageTag.putInt("stage", stockEntry.getKey());
                NbtList itemIds = new NbtList();
                for (String itemId : stockEntry.getValue()) itemIds.add(net.minecraft.nbt.NbtString.of(itemId));
                stageTag.put("items", itemIds);
                stockStages.add(stageTag);
            }
            tag.put("stockByStage", stockStages);
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
            d.seed = tag.contains("seed") ? tag.getLong("seed") : tag.getUuid("id").getMostSignificantBits();
            d.hired = tag.getBoolean("hired");
            d.hireUntil = tag.getLong("hireUntil");
            d.orderItem = tag.getString("orderItem").isEmpty() ? null : tag.getString("orderItem");
            d.orderDueDay = tag.getLong("orderDueDay");
            d.orderReady = tag.getBoolean("orderReady");
            d.pendingRequest = tag.getBoolean("pendingRequest");
            d.requestDeadline = tag.getLong("requestDeadline");

            NbtList stockStages = tag.getList("stockByStage", 10);
            for (int j = 0; j < stockStages.size(); j++) {
                NbtCompound stageTag = stockStages.getCompound(j);
                int stage = stageTag.getInt("stage");
                NbtList itemIds = stageTag.getList("items", 8);
                List<String> ids = new ArrayList<>();
                for (int k = 0; k < itemIds.size(); k++) ids.add(itemIds.getString(k));
                if (stage >= 1 && stage <= 5) d.stockByStage.put(stage, ids);
            }
            state.traders.put(tag.getUuid("id"), d);
        }
        return state;
    }

    public static class TraderData {
        public int reputation = 0;
        public int specialization = 0;
        public int strength = 1;
        public long createdDay = 0;
        public long seed = new Random().nextLong();
        public boolean hired = false;
        public long hireUntil = 0;
        public String orderItem;
        public long orderDueDay;
        public boolean orderReady;
        public boolean pendingRequest;
        public long requestDeadline;
        public final Map<Integer, List<String>> stockByStage = new HashMap<>();
    }
}
