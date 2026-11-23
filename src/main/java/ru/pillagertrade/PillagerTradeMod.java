package ru.pillagertrade;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
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

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            ServerWorld serverWorld = (ServerWorld) world;
            tickHiredGuards(serverWorld);
            if (world.getTime() % 24000L == 0L) {
                TradeState.get(serverWorld).onNewDay(serverWorld);
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
            out.writeString(item.getName().getString());
            out.writeInt(TradeState.priceFor(item, data));
        }
        ServerPlayNetworking.send(player, OPEN_SCREEN, out);
    }

    private static void handleAction(ServerPlayerEntity player, String action, UUID traderId, String itemId, int amount, int offer) {
        ServerWorld world = player.getServerWorld();
        TradeState state = TradeState.get(world);
        TradeState.TraderData data = state.get(traderId);
        if (data == null) return;
        if (!(world.getEntity(traderId) instanceof IllagerEntity pillager) || !pillager.isAlive()
                || player.squaredDistanceTo(pillager) > 64.0) return;

        if ("buy".equals(action)) {
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
            int price = TradeState.priceFor(item, data) * amount;
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
            int base = 10;
            double chance = MathHelper.clamp(0.65 - Math.max(0, base - offer) * 0.025 + data.reputation * 0.002, 0.05, 0.9);
            double roll = world.getRandom().nextDouble();
            if (roll < chance) {
                data.reputation = Math.min(100, data.reputation + 1);
                player.sendMessage(Text.literal("Поборник согласился на предложенную цену!"), true);
            } else if (roll < chance + 0.20) {
                player.sendMessage(Text.literal("Поборник возмутился и повысил цену!"), true);
            } else {
                player.sendMessage(Text.literal("Поборник отказался торговаться."), true);
            }
            state.markDirty();
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
                        if (world.isSpaceEmpty(guard, candidate)
                                && world.getBlockState(feet).getFluidState().isEmpty()) {
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
                            && hostile != guard
                            && hostile != owner);
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
