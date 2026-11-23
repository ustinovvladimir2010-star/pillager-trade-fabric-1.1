package ru.pillagertrade;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.Box;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.BlockItem;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.screen.ScreenHandlerType;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.registry.Registry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.registry.Registries;

import java.util.List;
import java.util.UUID;

public class PillagerTradeMod implements ModInitializer {
    public static final String MOD_ID = "pillager_trade";
    public static final Identifier OPEN_SCREEN = new Identifier(MOD_ID, "open_screen");
    public static final Identifier ACTION = new Identifier(MOD_ID, "action");
    public static final Identifier SYNC = new Identifier(MOD_ID, "sync");
    public static final Identifier ORDER_START = new Identifier(MOD_ID, "order_start");
    private static final String BARON_TAG = "pillager_trade_crime_baron";
    public static final Block ORDER_TABLE = Registry.register(Registries.BLOCK,
            new Identifier(MOD_ID, "order_table"),
            new OrderTableBlock(AbstractBlock.Settings.create().strength(2.5f).requiresTool()));
    public static final net.minecraft.item.Item ORDER_TABLE_ITEM = Registry.register(Registries.ITEM,
            new Identifier(MOD_ID, "order_table"), new BlockItem(ORDER_TABLE, new net.minecraft.item.Item.Settings()));
    public static final BlockEntityType<OrderTableBlockEntity> ORDER_TABLE_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, new Identifier(MOD_ID, "order_table"),
            BlockEntityType.Builder.create(OrderTableBlockEntity::new, ORDER_TABLE).build(null));
    public static final ScreenHandlerType<OrderTableScreenHandler> ORDER_TABLE_SCREEN =
            ScreenHandlerRegistry.registerExtended(new Identifier(MOD_ID, "order_table"),
                    (syncId, inventory, buf) -> {
                        net.minecraft.util.math.BlockPos pos = buf.readBlockPos();
                        int count = buf.readVarInt();
                        List<OrderTableScreenHandler.TraderOption> options = new java.util.ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            options.add(new OrderTableScreenHandler.TraderOption(buf.readUuid(),
                                    buf.readString(128), buf.readInt(), buf.readInt()));
                        }
                        net.minecraft.block.entity.BlockEntity be = inventory.player.getWorld().getBlockEntity(pos);
                        OrderTableBlockEntity table = be instanceof OrderTableBlockEntity found
                                ? found : new OrderTableBlockEntity(pos, ORDER_TABLE.getDefaultState());
                        return new OrderTableScreenHandler(syncId, inventory, table, options);
                    });

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(new Identifier(MOD_ID, "summon_order"), (server, player, handler, buf, responseSender) -> {
            net.minecraft.util.math.BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                if (player.currentScreenHandler instanceof OrderTableScreenHandler screen
                        && screen.getTable().getPos().equals(pos)) {
                    screen.getTable().startSummonOrder(player);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ORDER_START, (server, player, handler, buf, responseSender) -> {
            net.minecraft.util.math.BlockPos pos = buf.readBlockPos();
            int index = buf.readVarInt();
            server.execute(() -> {
                if (player.currentScreenHandler instanceof OrderTableScreenHandler screen
                        && screen.getTable().getPos().equals(pos)
                        && index >= 0 && index < screen.getTraders().size()) {
                    screen.getTable().startOrder(player, screen.getTraders().get(index).id());
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
            String action = buf.readString(32);
            UUID traderId = buf.readUuid();
            String itemId = buf.readString(128);
            int amount = MathHelper.clamp(buf.readInt(), 1, 64);
            int offer = MathHelper.clamp(buf.readInt(), 1, 999999);
            server.execute(() -> handleAction(player, action, traderId, itemId, amount, offer));
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(entity instanceof PillagerEntity) && !(entity instanceof VindicatorEntity)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            IllagerEntity pillager = (IllagerEntity) entity;
            if (player.isSneaking()) {
                if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                    toggleGuardFollow(serverPlayer, pillager);
                }
                return ActionResult.SUCCESS;
            }
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                openTrader(serverPlayer, pillager);
            }
            return ActionResult.SUCCESS;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity.getWorld() instanceof ServerWorld world) || !entity.getCommandTags().contains(BARON_TAG)) return;
            TradeState state = TradeState.get(world);
            state.setBaronDefeated(true);
            state.setBaronEntityId(null);
            LivingEntity attacker = entity.getAttacker();
            if (attacker instanceof ServerPlayerEntity player) {
                state.grantMarketInfluence(player.getUuid());
                int reward = 16 + world.getRandom().nextInt(17);
                ItemStack emeralds = new ItemStack(Items.EMERALD, reward);
                if (!player.getInventory().insertStack(emeralds)) player.dropItem(emeralds, false);
                player.sendMessage(Text.literal("Криминальный барон повержен! Вы получили " + reward
                        + " изумрудов и постоянный эффект «Рыночное влияние»: скидка 10% на заказы."), false);
            } else {
                world.getPlayers().forEach(player -> player.sendMessage(
                        Text.literal("Криминальный барон повержен, но награду не получил игрок."), false));
            }
        });

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            ServerWorld serverWorld = (ServerWorld) world;
            tickHiredGuards(serverWorld);
            TradeState state = TradeState.get(serverWorld);
            long day = world.getTime() / 24000L;
            if (world.getTime() % 24000L == 0L) state.onNewDay(serverWorld);

            if (!state.isBaronDefeated() && state.getBaronArrivalTime() == 0L
                    && state.countMaximumRankTraders(day) >= 5) {
                state.setBaronArrivalTime(world.getTime() + 24000L);
                serverWorld.getPlayers().forEach(player -> player.sendMessage(
                        Text.literal("§4[Угроза] §cКриминальный барон заметил рост вашей торговой сети. "
                                + "Он прибудет через 1 игровой день!"), false));
            }

            if (!state.isBaronDefeated() && state.getBaronArrivalTime() > 0L
                    && world.getTime() >= state.getBaronArrivalTime()
                    && state.getBaronEntityId() == null && !serverWorld.getPlayers().isEmpty()) {
                ServerPlayerEntity target = serverWorld.getPlayers().get(serverWorld.getRandom().nextInt(serverWorld.getPlayers().size()));
                VindicatorEntity boss = EntityType.VINDICATOR.create(serverWorld);
                if (boss != null) {
                    boss.refreshPositionAndAngles(target.getX() + 4.0, target.getY(), target.getZ() + 4.0,
                            serverWorld.getRandom().nextFloat() * 360.0F, 0.0F);
                    boss.addCommandTag(BARON_TAG);
                    boss.setCustomName(Text.literal("§4Криминальный барон"));
                    boss.setCustomNameVisible(true);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null)
                        boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(220.0);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE) != null)
                        boss.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(18.0);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED) != null)
                        boss.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.34);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_ARMOR) != null)
                        boss.getAttributeInstance(EntityAttributes.GENERIC_ARMOR).setBaseValue(12.0);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE) != null)
                        boss.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(0.75);
                    boss.setHealth(220.0F);
                    boss.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
                    boss.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                    boss.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                    boss.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    boss.equipStack(net.minecraft.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    serverWorld.spawnEntity(boss);
                    state.setBaronEntityId(boss.getUuid());
                    serverWorld.getPlayers().forEach(player -> player.sendMessage(
                            Text.literal("§4КРИМИНАЛЬНЫЙ БАРОН ПРИБЫЛ! §cОн намерен остановить вашу торговую сеть."), false));
                }
            }
        });
    }

    private static void openTrader(ServerPlayerEntity player, IllagerEntity pillager) {
        TradeState state = TradeState.get(player.getServerWorld());
        TradeState.TraderData data = state.getOrCreate(pillager);
        PacketByteBuf out = PacketByteBufs.create();
        out.writeUuid(pillager.getUuid());
        out.writeString(pillager.getName().getString());
        out.writeInt(data.reputation);
        out.writeInt(data.specialization);
        out.writeBoolean(data.hired);
        long day = player.getServerWorld().getTime() / 24000L;
        int stage = TradeState.getStage(day, data.reputation);
        out.writeLong(day);
        out.writeInt(stage);
        out.writeInt(TradeState.daysForNextStage(stage));
        out.writeInt(TradeState.reputationForNextStage(stage));
        List<Item> stock = state.getStock(data, stage);
        out.writeInt(countEmeralds(player));
        out.writeVarInt(stock.size());
        for (Item item : stock) {
            Identifier id = Registries.ITEM.getId(item);
            out.writeString(id.toString());
            out.writeString(displayItemName(item));
            out.writeInt(TradeState.priceFor(item, data, state.hasMarketInfluence(player.getUuid())));
        }
        TradeState.QuestData activeQuest = state.getQuest(player.getUuid());
        out.writeBoolean(activeQuest != null);
        if (activeQuest != null) {
            Item questItem = Registries.ITEM.getOrEmpty(new Identifier(activeQuest.itemId)).orElse(Items.AIR);
            out.writeString(activeQuest.itemId);
            out.writeString(displayItemName(questItem));
            out.writeInt(activeQuest.amount);
            out.writeInt(activeQuest.reward);
        } else if (!stock.isEmpty()) {
            Item questItem = stock.get(player.getRandom().nextInt(stock.size()));
            int amount = Math.min(4, questItem.getMaxCount());
            out.writeString(Registries.ITEM.getId(questItem).toString());
            out.writeString(displayItemName(questItem));
            out.writeInt(amount);
            out.writeInt(Math.max(4, TradeState.orderPriceFor(questItem) * amount * 2));
        } else {
            out.writeString("");
            out.writeString("Нет доступных заданий");
            out.writeInt(0);
            out.writeInt(0);
        }
        ServerPlayNetworking.send(player, OPEN_SCREEN, out);
    }

    private static String displayItemName(Item item) {
        String original = item.getName().getString();
        if (original == null || original.isBlank()) original = Registries.ITEM.getId(item).getPath();
        String lower = original.toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, String> exact = java.util.Map.ofEntries(
                java.util.Map.entry("iron ingot", "железный слиток"),
                java.util.Map.entry("gold ingot", "золотой слиток"),
                java.util.Map.entry("diamond", "алмаз"),
                java.util.Map.entry("emerald", "изумруд"),
                java.util.Map.entry("coal", "уголь"),
                java.util.Map.entry("copper ingot", "медный слиток"),
                java.util.Map.entry("bread", "хлеб"),
                java.util.Map.entry("torch", "факел"),
                java.util.Map.entry("arrow", "стрела"),
                java.util.Map.entry("crossbow", "арбалет"),
                java.util.Map.entry("iron sword", "железный меч"),
                java.util.Map.entry("diamond sword", "алмазный меч"),
                java.util.Map.entry("oak planks", "дубовые доски"),
                java.util.Map.entry("cobblestone", "булыжник"),
                java.util.Map.entry("leather", "кожа"),
                java.util.Map.entry("string", "нить"),
                java.util.Map.entry("ender pearl", "жемчуг эндера"),
                java.util.Map.entry("amethyst shard", "осколок аметиста")
        );
        if (exact.containsKey(lower)) return exact.get(lower);

        String path = Registries.ITEM.getId(item).getPath().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        java.util.Map<String, String> words = java.util.Map.ofEntries(
                java.util.Map.entry("iron", "железный"), java.util.Map.entry("gold", "золотой"),
                java.util.Map.entry("copper", "медный"), java.util.Map.entry("diamond", "алмазный"),
                java.util.Map.entry("netherite", "незеритовый"), java.util.Map.entry("steel", "стальной"),
                java.util.Map.entry("wooden", "деревянный"), java.util.Map.entry("stone", "каменный"),
                java.util.Map.entry("sword", "меч"), java.util.Map.entry("axe", "топор"),
                java.util.Map.entry("pickaxe", "кирка"), java.util.Map.entry("shovel", "лопата"),
                java.util.Map.entry("hoe", "мотыга"), java.util.Map.entry("helmet", "шлем"),
                java.util.Map.entry("chestplate", "нагрудник"), java.util.Map.entry("leggings", "поножи"),
                java.util.Map.entry("boots", "ботинки"), java.util.Map.entry("ingot", "слиток"),
                java.util.Map.entry("nugget", "самородок"), java.util.Map.entry("ore", "руда"),
                java.util.Map.entry("block", "блок"), java.util.Map.entry("gem", "самоцвет"),
                java.util.Map.entry("crystal", "кристалл"), java.util.Map.entry("shard", "осколок"),
                java.util.Map.entry("dust", "пыль"), java.util.Map.entry("plate", "пластина"),
                java.util.Map.entry("gear", "шестерня"), java.util.Map.entry("machine", "механизм"),
                java.util.Map.entry("bow", "лук"), java.util.Map.entry("crossbow", "арбалет"),
                java.util.Map.entry("shield", "щит"), java.util.Map.entry("apple", "яблоко"),
                java.util.Map.entry("bread", "хлеб"), java.util.Map.entry("berry", "ягода"),
                java.util.Map.entry("potion", "зелье"), java.util.Map.entry("staff", "посох")
        );
        StringBuilder translated = new StringBuilder();
        for (String word : path.split(" ")) {
            String value = words.getOrDefault(word, word);
            if (translated.length() > 0) translated.append(' ');
            translated.append(value);
        }
        return translated.toString();
    }


    private static void handleAction(ServerPlayerEntity player, String action, UUID traderId, String itemId, int amount, int offer) {
        ServerWorld world = player.getServerWorld();
        TradeState state = TradeState.get(world);
        TradeState.TraderData data = state.get(traderId);
        if (data == null) return;
        if (!(world.getEntity(traderId) instanceof IllagerEntity pillager) || !pillager.isAlive()
                || player.squaredDistanceTo(pillager) > 64.0) return;

        if ("quest_accept".equals(action)) {
            Item questItem;
            try { questItem = Registries.ITEM.getOrEmpty(new Identifier(itemId)).orElse(Items.AIR); }
            catch (IllegalArgumentException exception) { return; }
            long currentDay = world.getTime() / 24000L;
            int currentStage = TradeState.getStage(currentDay, data.reputation);
            boolean questWasOffered = state.getStock(data, currentStage).contains(questItem);
            int questAmount = Math.max(1, Math.min(4, Math.min(64, amount)));
            int questReward = Math.max(4, TradeState.orderPriceFor(questItem) * questAmount * 2);
            if (questItem == Items.AIR || !questWasOffered
                    || !state.acceptQuest(player.getUuid(), itemId, questAmount, questReward)) {
                player.sendMessage(Text.literal("У вас уже есть активное задание или предмет недоступен."), true);
            } else {
                player.sendMessage(Text.literal("Задание принято: найдите " + questAmount + " × " + displayItemName(questItem)
                        + ". Награда: " + questReward + " изумрудов."), false);
            }
            return;
        } else if ("quest_complete".equals(action)) {
            TradeState.QuestData quest = state.getQuest(player.getUuid());
            if (quest == null) {
                player.sendMessage(Text.literal("У вас нет активного задания."), true);
                return;
            }
            Item questItem;
            try { questItem = Registries.ITEM.getOrEmpty(new Identifier(quest.itemId)).orElse(Items.AIR); }
            catch (IllegalArgumentException exception) { return; }
            int found = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isOf(questItem)) found += stack.getCount();
            }
            if (questItem == Items.AIR || found < quest.amount) {
                player.sendMessage(Text.literal("Пока недостаточно предметов для выполнения задания."), true);
                return;
            }
            int remaining = quest.amount;
            for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isOf(questItem)) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.decrement(take);
                    remaining -= take;
                }
            }
            int reward = quest.reward;
            state.finishQuest(player.getUuid());
            giveItem(player, new ItemStack(Items.EMERALD, reward));
            player.sendMessage(Text.literal("Задание выполнено! Получено " + reward + " изумрудов."), false);
            syncEmeraldBalance(player);
            return;
        } else if ("buy".equals(action)) {
            Identifier identifier;
            try {
                identifier = new Identifier(itemId);
            } catch (IllegalArgumentException exception) {
                return;
            }
            Item item = Registries.ITEM.getOrEmpty(identifier).orElse(Items.AIR);
            long day = world.getTime() / 24000L;
            int stage = TradeState.getStage(day, data.reputation);
            boolean offeredByTrader = false;
            for (int unlockedStage = 1; unlockedStage <= stage; unlockedStage++) {
                if (state.getStock(data, unlockedStage).contains(item)) {
                    offeredByTrader = true;
                    break;
                }
            }
            if (item == Items.AIR || !offeredByTrader) return;
            amount = MathHelper.clamp(amount, 1, Math.min(64, item.getMaxCount()));
            int price = TradeState.priceFor(item, data, state.hasMarketInfluence(player.getUuid())) * amount;
            if (!removeEmeralds(player, price)) {
                player.sendMessage(Text.translatable("message.pillager_trade.not_enough"), true);
                syncEmeraldBalance(player);
                return;
            }
            giveItem(player, new ItemStack(item, amount));
            data.reputation = Math.min(100, data.reputation + 1);
            state.markDirty();
            player.sendMessage(Text.translatable("message.pillager_trade.bought"), true);
            syncEmeraldBalance(player);
        } else if ("hire".equals(action)) {
            if (data.hired) {
                player.sendMessage(Text.literal("Этот поборник уже нанят."), true);
                return;
            }
            int daily = Math.max(2, 12 - data.reputation / 15 - data.strength / 2);
            if (!removeEmeralds(player, daily)) {
                player.sendMessage(Text.literal("Недостаточно изумрудов для найма."), true);
                syncEmeraldBalance(player);
                return;
            }
            data.hired = true;
            data.hireOwner = player.getUuid();
            data.hireUntil = world.getTime() + 24000L;
            data.reputation = Math.min(100, data.reputation + 2);
            state.markDirty();
            player.sendMessage(Text.literal("Поборник нанят на 1 игровой день. Оплата: " + daily + " изумрудов."), true);
            syncEmeraldBalance(player);
        } else if ("bargain".equals(action)) {
            Item bargainItem;
            try { bargainItem = Registries.ITEM.getOrEmpty(new Identifier(itemId)).orElse(Items.AIR); }
            catch (IllegalArgumentException exception) { return; }
            String bargainKey = Registries.ITEM.getId(bargainItem).toString();
            if (bargainItem == Items.AIR || !data.itemDiscounts.containsKey(bargainKey)) {
                int base = 10;
                double chance = MathHelper.clamp(0.65 - Math.max(0, base - offer) * 0.025 + data.reputation * 0.002, 0.05, 0.9);
                double roll = world.getRandom().nextDouble();
                if (roll < chance) {
                    data.itemDiscounts.put(bargainKey, 10);
                    data.reputation = Math.min(100, data.reputation + 1);
                    player.sendMessage(Text.literal("Торг удался! Скидка 10% на " + displayItemName(bargainItem)
                            + " только у этого торговца. Повторный торг за этот предмет невозможен."), true);
                } else if (roll < chance + 0.20) {
                    player.sendMessage(Text.literal("Поборник возмутился. В этот раз торг не удался."), true);
                } else {
                    player.sendMessage(Text.literal("Поборник отказался торговаться."), true);
                }
                state.markDirty();
            } else {
                player.sendMessage(Text.literal("Вы уже торговались за этот предмет у этого поборника."), true);
            }
        } else if ("request".equals(action)) {
            data.orderItem = itemId;
            data.orderDueDay = world.getTime() / 24000L + 5 + world.getRandom().nextInt(16);
            player.sendMessage(Text.literal("Заказ принят. Ожидайте от 5 до 20 игровых дней."), true);
            state.markDirty();
        }
    }


    /**
     * Keeps hired pillagers assigned to their owner and makes them defend the owner
     * against nearby hostile mobs. All decisions are server-side.
     */
    private static void toggleGuardFollow(ServerPlayerEntity player, IllagerEntity guard) {
        TradeState state = TradeState.get(player.getServerWorld());
        TradeState.TraderData data = state.get(guard.getUuid());
        if (data == null || !data.hired || data.hireUntil <= player.getServerWorld().getTime()) {
            player.sendMessage(Text.literal("Сначала наймите этого поборника."), true);
            return;
        }
        if (data.hireOwner == null || !data.hireOwner.equals(player.getUuid())) {
            player.sendMessage(Text.literal("Вы не можете отдавать приказы чужому охраннику."), true);
            return;
        }
        data.staying = !data.staying;
        state.markDirty();
        guard.getNavigation().stop();
        player.sendMessage(Text.literal(data.staying
                ? "Поборник остаётся на месте."
                : "Поборник следует за вами."), true);
    }

    private static boolean teleportGuardNearOwner(ServerWorld world, IllagerEntity guard, ServerPlayerEntity owner) {
        for (int radius = 1; radius <= 3; radius++) {
            for (int yOffset = 0; yOffset <= 2; yOffset++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        double px = Math.floor(owner.getX()) + x + 0.5;
                        double py = Math.floor(owner.getY()) + yOffset;
                        double pz = Math.floor(owner.getZ()) + z + 0.5;
                        net.minecraft.util.math.Box candidate = guard.getBoundingBox()
                                .offset(px - guard.getX(), py - guard.getY(), pz - guard.getZ());
                        net.minecraft.util.math.BlockPos feet = new net.minecraft.util.math.BlockPos(
                                (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
                        net.minecraft.util.math.BlockPos floor = feet.down();
                        if (world.isSpaceEmpty(guard, candidate)
                                && world.getBlockState(feet).getFluidState().isEmpty()
                                && world.getBlockState(floor).isSolidBlock(world, floor)) {
                            guard.requestTeleport(px, py, pz);
                            guard.getNavigation().stop();
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void tickHiredGuards(ServerWorld world) {
        TradeState state = TradeState.get(world);
        boolean changed = false;

        for (var entry : state.getTraders().entrySet()) {
            TradeState.TraderData data = entry.getValue();
            if (!data.hired) continue;

            // Expire contracts by world time, not only at the day-boundary callback.
            if (world.getTime() >= data.hireUntil) {
                data.hired = false;
                data.hireOwner = null;
                data.hireUntil = 0L;
                if (world.getEntity(entry.getKey()) instanceof IllagerEntity expiredGuard) {
                    expiredGuard.setTarget(null);
                    expiredGuard.getNavigation().stop();
                }
                changed = true;
                continue;
            }
            if (data.hireOwner == null) continue;

            // Both vanilla guard types are supported: Pillagers keep their crossbow,
            // Vindicators keep their axe and use their own melee combat AI.
            if (!(world.getEntity(entry.getKey()) instanceof IllagerEntity guard) || !guard.isAlive()) {
                continue;
            }
            ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(data.hireOwner);
            if (owner == null || !owner.isAlive() || owner.getWorld() != world) {
                guard.setTarget(null);
                guard.getNavigation().stop();
                continue;
            }

            if (!data.staying && guard.squaredDistanceTo(owner) > 48.0 * 48.0) {
                if (teleportGuardNearOwner(world, guard, owner)) continue;
            }

            // Search around both the owner and the guard so a guard can intercept
            // threats approaching from either direction.
            Box searchArea = owner.getBoundingBox().expand(14.0).union(guard.getBoundingBox().expand(10.0));
            List<HostileEntity> threats = world.getEntitiesByClass(HostileEntity.class, searchArea,
                    hostile -> hostile.isAlive()
                            && !(hostile instanceof IllagerEntity)
                            && hostile != guard);
            HostileEntity nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (HostileEntity hostile : threats) {
                boolean threateningOwner = hostile.getTarget() == owner
                        || hostile.squaredDistanceTo(owner) <= 100.0
                        || hostile.getTarget() == guard;
                if (!threateningOwner) continue;
                double distance = guard.squaredDistanceTo(hostile);
                if (distance < nearestDistance) {
                    nearest = hostile;
                    nearestDistance = distance;
                }
            }

            if (nearest != null) {
                if (guard.getTarget() != nearest) guard.setTarget(nearest);
                // Do not issue follow commands while combat AI is active.
                continue;
            }

            if (data.staying) {
                if (guard.getNavigation().isFollowingPath()) guard.getNavigation().stop();
                continue;
            }

            LivingEntity currentTarget = guard.getTarget();
            if (currentTarget instanceof HostileEntity hostile
                    && hostile.isAlive() && !(hostile instanceof IllagerEntity)) {
                // Finish an already-started fight before returning to the owner.
                continue;
            }
            if (currentTarget != null) guard.setTarget(null);

            double distanceToOwner = guard.squaredDistanceTo(owner);
            if (distanceToOwner > 36.0) {
                // Re-path periodically instead of resetting navigation every tick.
                if (world.getTime() % 10L == 0L) {
                    guard.getNavigation().startMovingTo(owner, guard instanceof VindicatorEntity ? 1.12 : 1.08);
                }
                if (guard.isOnGround() && guard.horizontalCollision) {
                    guard.getJumpControl().setActive();
                }
            } else if (distanceToOwner < 12.25 && guard.getNavigation().isFollowingPath()) {
                guard.getNavigation().stop();
            }
        }

        if (changed) state.markDirty();
    }

    public static int countEmeralds(ServerPlayerEntity player) {
        int found = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.EMERALD)) found += stack.getCount();
        }
        return found;
    }

    public static void syncEmeraldBalance(ServerPlayerEntity player) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeInt(countEmeralds(player));
        ServerPlayNetworking.send(player, SYNC, out);
    }

    public static boolean removeEmeralds(ServerPlayerEntity player, int amount) {
        int found = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.EMERALD)) found += stack.getCount();
        }
        if (found < amount) return false;
        int remaining = amount;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.EMERALD)) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }
        return true;
    }

    public static void giveItem(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
    }
}
