package ru.pillagertrade;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

public class OrderTableScreenHandler extends ScreenHandler {
    private final OrderTableBlockEntity table;
    private final List<TraderOption> traders;

    public OrderTableScreenHandler(int syncId, PlayerInventory playerInventory,
                                   OrderTableBlockEntity table, List<TraderOption> traders) {
        super(PillagerTradeMod.ORDER_TABLE_SCREEN, syncId);
        this.table = table;
        this.traders = traders;
        addSlot(new Slot(table.getInventory(), 0, 44, 35));
        for (int i = 0; i < 9; i++) {
            final int index = i + 1;
            addSlot(new Slot(table.getInventory(), index, 116 + (i % 3) * 18, 17 + (i / 3) * 18) {
                @Override public boolean canInsert(ItemStack stack) { return false; }
                @Override public boolean canTakeItems(PlayerEntity player) { return true; }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 162));
        }
    }

    public OrderTableBlockEntity getTable() { return table; }
    public List<TraderOption> getTraders() { return traders; }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        ItemStack result = ItemStack.EMPTY;
        Slot source = slots.get(slot);
        if (source != null && source.hasStack()) {
            ItemStack stack = source.getStack();
            result = stack.copy();
            if (slot < 10) {
                if (!insertItem(stack, 10, slots.size(), true)) return ItemStack.EMPTY;
            } else if (!insertItem(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) source.setStack(ItemStack.EMPTY);
            else source.markDirty();
        }
        return result;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return table.getWorld() != null && player.squaredDistanceTo(
                table.getPos().getX() + 0.5, table.getPos().getY() + 0.5, table.getPos().getZ() + 0.5) <= 64.0;
    }

    public record TraderOption(UUID id, String name, int reputation, int strength) {}
}