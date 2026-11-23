package ru.pillagertrade;

import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.PersistentState;
import net.minecraft.registry.Registries;

import java.util.*;

public class TradeState extends PersistentState {
    private static final int STOCK_SIZE = 8;
    private static final int[] STAGE_DAYS = {0, 10, 25, 45, 70};
    private static final int[] STAGE_REPUTATION = {0, 15, 30, 50, 70};

    private final Map<UUID, TraderData> traders = new HashMap<>();
    private final Set<UUID> marketInfluencePlayers = new HashSet<>();
    private final Map<UUID, QuestData> activeQuests = new HashMap<>();
    private long baronArrivalTime = 0L;
    private UUID baronEntityId;
    private boolean baronDefeated = false;

    public boolean hasMarketInfluence(UUID playerId) {
        return marketInfluencePlayers.contains(playerId);
    }

    public void grantMarketInfluence(UUID playerId) {
        if (marketInfluencePlayers.add(playerId)) markDirty();
    }

    public QuestData getQuest(UUID playerId) { return activeQuests.get(playerId); }

    public boolean acceptQuest(UUID playerId, String itemId, int amount, int reward) {
        if (activeQuests.containsKey(playerId)) return false;
        QuestData quest = new QuestData();
        quest.itemId = itemId;
        quest.amount = Math.max(1, Math.min(64, amount));
        quest.reward = Math.max(1, reward);
        activeQuests.put(playerId, quest);
        markDirty();
        return true;
    }

    public void finishQuest(UUID playerId) {
        activeQuests.remove(playerId);
        markDirty();
    }

    public static class QuestData {
        public String itemId = "";
        public int amount;
        public int reward;
    }

    public long getBaronArrivalTime() { return baronArrivalTime; }
    public void setBaronArrivalTime(long value) { baronArrivalTime = value; markDirty(); }
    public UUID getBaronEntityId() { return baronEntityId; }
    public void setBaronEntityId(UUID value) { baronEntityId = value; markDirty(); }
    public boolean isBaronDefeated() { return baronDefeated; }
    public void setBaronDefeated(boolean value) { baronDefeated = value; markDirty(); }

    public int countMaximumRankTraders(long worldDay) {
        int count = 0;
        for (TraderData data : traders.values()) {
            if (getStage(worldDay, data.reputation) >= 5) count++;
        }
        return count;
    }

    public static TradeState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TradeState::fromNbt, TradeState::new, "pillager_trade_state");
    }

    public TraderData getOrCreate(IllagerEntity entity) {
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

    public Map<UUID, TraderData> getTraders() {
        return Collections.unmodifiableMap(traders);
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

        // If a specialization has a small item pool, show fewer offers instead
        // of padding the list with unrelated goods.
        List<String> ids = new ArrayList<>();
        for (Item item : selected) ids.add(Registries.ITEM.getId(item).toString());
        return ids;
    }

    private static boolean matchesSpecialization(Item item, int specialization) {
        String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);

        // Ore blocks stay with the prospector; functional redstone blocks stay with the mechanic.
        // All other block items (including doors and concrete powder) go to the quartermaster.
        boolean oreBlock = containsAny(path, "ore", "raw_");
        boolean machinery = containsAny(path, "redstone", "piston", "repeater",
                "comparator", "hopper", "observer", "dispenser", "dropper", "rail",
                "lever", "button", "pressure_plate", "gear", "machine", "circuit",
                "copper_bulb", "lightning_rod", "target");
        boolean building = (item instanceof BlockItem && !oreBlock && !machinery)
                || (containsAny(path, "concrete", "terracotta", "planks", "log", "wood",
                "stone", "copper_block", "glass", "wool", "slab", "stairs", "wall",
                "fence", "door", "trapdoor", "tile", "marble", "limestone", "basalt",
                "deepslate", "sandstone", "quartz_block", "clay", "scaffolding", "pane",
                "carpet") && !oreBlock && !machinery);

        // Explicit categories; avoid broad words such as "powder", "dust" or "flower":
        // they match unrelated items like concrete powder and decorative blocks.
        boolean food = containsAny(path, "bread", "food", "stew", "meat", "fish",
                "apple", "carrot", "potato", "wheat", "seed", "cookie", "berry",
                "berries", "melon", "pumpkin", "beetroot", "honey_bottle");
        boolean combat = containsAny(path, "sword", "battle_axe", "war_axe",
                "crossbow", "shield", "helmet", "chestplate", "leggings", "boots",
                "armor", "armour", "trident", "mace")
                || path.equals("bow") || path.endsWith("_bow");
        boolean mining = containsAny(path, "ore", "ingot", "raw_", "nugget", "gem",
                "diamond", "emerald", "coal", "iron", "gold", "lapis", "quartz",
                "amethyst", "ancient_debris");
        boolean alchemy = containsAny(path, "potion", "splash_potion", "lingering_potion",
                "glass_bottle", "nether_wart", "blaze_powder", "blaze_rod",
                "glowstone_dust", "fermented_spider_eye", "magma_cream",
                "ghast_tear", "spider_eye", "slime_ball", "phantom_membrane",
                "dragon_breath", "rabbit_foot", "turtle_helmet");

        // Upgrade components and smithing templates are late-game materials,
        // not ordinary weapons for the early weapon trader.
        boolean upgradeMaterial = containsAny(path, "upgrade", "smithing_template",
                "smithing", "template", "netherite", "weapon_augment", "weapon_upgrade",
                "augment", "reforg", "reinforce", "awakening", "infusion",
                "mythic_core", "legendary_core", "endgame_component");

        if (specialization != 6 && building) return false;
        if (upgradeMaterial && specialization != 5) return false;

        return switch (specialization) {
            case 0 -> food || containsAny(path, "torch", "lantern", "bed", "bucket", "campfire");
            case 1 -> combat && !upgradeMaterial;
            case 2 -> mining;
            case 3 -> alchemy;
            case 4 -> machinery;
            case 5 -> {
                boolean modded = !Registries.ITEM.getId(item).getNamespace().equals("minecraft");
                Rarity rarity = item.getDefaultStack().getRarity();
                yield upgradeMaterial || modded || rarity == Rarity.UNCOMMON
                        || rarity == Rarity.RARE || rarity == Rarity.EPIC;
            }
            case 6 -> building;
            default -> false;
        };
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
        String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);
        boolean modded = !Registries.ITEM.getId(item).getNamespace().equals("minecraft");

        // Hard gates for end-game progression materials, regardless of rarity or stack size.
        if (containsAny(path, "netherite", "ancient_debris", "smithing_template",
                "smithing", "template", "upgrade", "weapon_augment", "weapon_upgrade",
                "augment", "reforg", "reinforce", "awakening", "infusion",
                "mythic", "legendary", "dragonsteel", "chaos", "awakened",
                "ultimate", "endgame_component")) return 5;

        int tier = 1;
        if (containsAny(path, "diamond", "obsidian", "reinforced", "steel", "titanium")) tier = 3;
        if (containsAny(path, "iron", "gold", "copper", "bronze", "silver")) tier = Math.max(tier, 2);

        // Unclassified modded items are held back until the middle stages.
        // Basic modded materials can still appear early and keep every stage varied.
        if (modded && tier == 1 && !containsAny(path, "wood", "stone", "cobble",
                "flint", "copper", "iron", "coal", "leather", "string", "cloth",
                "fiber", "bread", "food", "herb", "berry", "wheat", "torch",
                "cobble", "plank", "log", "stick", "flour", "salt", "copper_ore",
                "iron_ore", "tin_ore", "raw_copper", "raw_iron")) {
            tier = 3;
        }

        if (item.getMaxDamage() > 0) tier = Math.max(tier, 2);
        if (item.getMaxDamage() > 500) tier = Math.max(tier, 3);
        if (item.getMaxCount() <= 16) tier = Math.max(tier, 2);

        Rarity rarity = item.getDefaultStack().getRarity();
        if (rarity == Rarity.UNCOMMON) tier = Math.max(tier, 2);
        else if (rarity == Rarity.RARE) tier = Math.max(tier, 3);
        else if (rarity == Rarity.EPIC) tier = Math.max(tier, 4);

        return Math.min(5, tier);
    }

    public void onNewDay(ServerWorld world) {
        long day = world.getTime() / 24000L;
        for (TraderData data : traders.values()) {
            if (data.hired && world.getTime() >= data.hireUntil) {
                data.hired = false;
                data.hireOwner = null;
                data.hireUntil = 0L;
            }
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

    public static int orderPriceFor(Item item) {
        String path = Registries.ITEM.getId(item).getPath().toLowerCase(Locale.ROOT);
        int value;
        if (containsAny(path, "netherite", "dragonsteel", "mythic", "legendary", "chaos", "ultimate")) value = 48;
        else if (containsAny(path, "diamond", "emerald", "nether_star", "elytra", "totem")) value = 24;
        else if (containsAny(path, "ancient_debris", "beacon", "shulker", "trident", "mace")) value = 20;
        else if (containsAny(path, "gold", "iron", "amethyst", "blaze_rod", "ender_pearl", "obsidian")) value = 6;
        else if (containsAny(path, "copper", "coal", "lapis", "redstone", "quartz", "leather", "string")) value = 3;
        else if (containsAny(path, "bread", "torch", "planks", "cobblestone", "dirt", "sand", "gravel", "stick", "wheat", "seeds")) value = 1;
        else value = 2;

        int progressionTier = getItemTier(item);
        int tierMinimum = switch (progressionTier) {
            case 1 -> 1;
            case 2 -> 4;
            case 3 -> 9;
            case 4 -> 18;
            default -> 30;
        };
        value = Math.max(value, tierMinimum);

        Rarity rarity = item.getDefaultStack().getRarity();
        if (rarity == Rarity.UNCOMMON) value = Math.max(value, 5);
        else if (rarity == Rarity.RARE) value = Math.max(value, 12);
        else if (rarity == Rarity.EPIC) value = Math.max(value, 28);
        if (item.getMaxDamage() > 0) value = Math.max(value, item.getMaxDamage() > 500 ? 14 : 7);
        if (item.getMaxCount() <= 16) value = Math.max(value, 5);
        return MathHelper.clamp(value, 1, 64);
    }

    public static int orderPriceFor(Item item, TraderData data, boolean marketInfluence) {
        int base = orderPriceFor(item);
        int itemDiscount = data.itemDiscounts.getOrDefault(Registries.ITEM.getId(item).toString(), 0);
        int totalDiscount = Math.min(10, itemDiscount + (marketInfluence ? 10 : 0));
        return Math.max(1, (int) Math.ceil(base * (100 - totalDiscount) / 100.0));
    }

    public static int priceFor(Item item, TraderData data) {
        return priceFor(item, data, false);
    }

    public static int priceFor(Item item, TraderData data, boolean marketInfluence) {
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

        int normalPrice = Math.max(1, base - data.reputation / 25 + data.strength);
        int itemDiscount = data.itemDiscounts.getOrDefault(Registries.ITEM.getId(item).toString(), 0);
        int totalDiscount = Math.min(10, itemDiscount + (marketInfluence ? 10 : 0));
        return Math.max(1, (int) Math.ceil(normalPrice * (100 - totalDiscount) / 100.0));
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
            tag.putInt("tradeDiscount", 0);
            NbtCompound discounts = new NbtCompound();
            for (Map.Entry<String, Integer> discount : d.itemDiscounts.entrySet()) discounts.putInt(discount.getKey(), discount.getValue());
            tag.put("itemDiscounts", discounts);
            tag.putLong("createdDay", d.createdDay);
            tag.putLong("seed", d.seed);
            tag.putBoolean("hired", d.hired);
            tag.putBoolean("staying", d.staying);
            tag.putLong("hireUntil", d.hireUntil);
            if (d.hireOwner != null) tag.putUuid("hireOwner", d.hireOwner);
            tag.putString("orderItem", d.orderItem == null ? "" : d.orderItem);
            tag.putLong("orderDueDay", d.orderDueDay);
            tag.putBoolean("orderReady", d.orderReady);
            tag.putBoolean("pendingRequest", d.pendingRequest);
            tag.putLong("requestDeadline", d.requestDeadline);
            tag.putInt("stockVersion", 5);

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
        NbtList influence = new NbtList();
        for (UUID id : marketInfluencePlayers) influence.add(net.minecraft.nbt.NbtString.of(id.toString()));
        nbt.put("marketInfluencePlayers", influence);
        nbt.putLong("baronArrivalTime", baronArrivalTime);
        if (baronEntityId != null) nbt.putUuid("baronEntityId", baronEntityId);
        nbt.putBoolean("baronDefeated", baronDefeated);
        NbtList quests = new NbtList();
        for (Map.Entry<UUID, QuestData> entry : activeQuests.entrySet()) {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("player", entry.getKey());
            tag.putString("item", entry.getValue().itemId);
            tag.putInt("amount", entry.getValue().amount);
            tag.putInt("reward", entry.getValue().reward);
            quests.add(tag);
        }
        nbt.put("activeQuests", quests);
        return nbt;
    }

    public static TradeState fromNbt(NbtCompound nbt) {
        TradeState state = new TradeState();
        NbtList influence = nbt.getList("marketInfluencePlayers", 8);
        for (int i = 0; i < influence.size(); i++) {
            try { state.marketInfluencePlayers.add(UUID.fromString(influence.getString(i))); }
            catch (IllegalArgumentException ignored) { }
        }
        state.baronArrivalTime = nbt.getLong("baronArrivalTime");
        if (nbt.containsUuid("baronEntityId")) state.baronEntityId = nbt.getUuid("baronEntityId");
        state.baronDefeated = nbt.getBoolean("baronDefeated");
        NbtList quests = nbt.getList("activeQuests", 10);
        for (int i = 0; i < quests.size(); i++) {
            NbtCompound tag = quests.getCompound(i);
            if (!tag.containsUuid("player")) continue;
            QuestData quest = new QuestData();
            quest.itemId = tag.getString("item");
            quest.amount = tag.getInt("amount");
            quest.reward = tag.getInt("reward");
            state.activeQuests.put(tag.getUuid("player"), quest);
        }
        NbtList list = nbt.getList("traders", 10);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            if (!tag.containsUuid("id")) continue;
            TraderData d = new TraderData();
            d.reputation = tag.getInt("reputation");
            d.specialization = tag.getInt("specialization");
            d.strength = tag.getInt("strength");
            d.tradeDiscount = 0;
            NbtCompound discounts = tag.getCompound("itemDiscounts");
            for (String key : discounts.getKeys()) d.itemDiscounts.put(key, MathHelper.clamp(discounts.getInt(key), 0, 10));
            d.createdDay = tag.getLong("createdDay");
            d.seed = tag.contains("seed") ? tag.getLong("seed") : tag.getUuid("id").getMostSignificantBits();
            d.hired = tag.getBoolean("hired");
            d.staying = tag.getBoolean("staying");
            d.hireUntil = tag.getLong("hireUntil");
            if (tag.containsUuid("hireOwner")) d.hireOwner = tag.getUuid("hireOwner");
            d.orderItem = tag.getString("orderItem").isEmpty() ? null : tag.getString("orderItem");
            d.orderDueDay = tag.getLong("orderDueDay");
            d.orderReady = tag.getBoolean("orderReady");
            d.pendingRequest = tag.getBoolean("pendingRequest");
            d.requestDeadline = tag.getLong("requestDeadline");

            // Regenerate legacy assortments once so old traders lose unrelated items
            // and receive the new specialization-focused stock.
            NbtList stockStages = tag.getInt("stockVersion") >= 5
                    ? tag.getList("stockByStage", 10) : new NbtList();
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
        public int tradeDiscount = 0; // legacy field, no longer used
        public final Map<String, Integer> itemDiscounts = new HashMap<>();
        public long createdDay = 0;
        public long seed = new Random().nextLong();
        public boolean hired = false;
        public boolean staying = false;
        public UUID hireOwner;
        public long hireUntil = 0;
        public String orderItem;
        public long orderDueDay;
        public boolean orderReady;
        public boolean pendingRequest;
        public long requestDeadline;
        public final Map<Integer, List<String>> stockByStage = new HashMap<>();
    }
}
