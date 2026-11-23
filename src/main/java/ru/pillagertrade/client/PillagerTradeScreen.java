package ru.pillagertrade.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.text.Text;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import ru.pillagertrade.PillagerTradeMod;

import java.util.List;
import java.util.UUID;

public class PillagerTradeScreen extends Screen {
    private final UUID trader;
    private final String traderName;
    private final int reputation;
    private final int specialization;
    private final boolean hired;
    private final long day;
    private final int stage;
    private final int nextStageDay;
    private final int nextStageReputation;
    private final List<StockEntry> stock;
    private int selected = 0;
    private int emeraldBalance;

    public PillagerTradeScreen(UUID trader, String name, int reputation, int specialization,
                               boolean hired, long day, int stage, int nextStageDay,
                               int nextStageReputation, int emeraldBalance, List<StockEntry> stock) {
        super(Text.translatable("screen.pillager_trade.title"));
        this.trader = trader;
        this.traderName = name;
        this.reputation = reputation;
        this.specialization = specialization;
        this.hired = hired;
        this.day = day;
        this.stage = stage;
        this.nextStageDay = nextStageDay;
        this.nextStageReputation = nextStageReputation;
        this.stock = stock;
        this.emeraldBalance = emeraldBalance;
    }

    @Override
    protected void init() {
        int left = (width - 360) / 2;
        int top = (height - 300) / 2;

        for (int i = 0; i < Math.min(8, stock.size()); i++) {
            final int index = i;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(stock.get(i).name),
                    b -> selected = index)
                    .dimensions(left + 12, top + 74 + i * 19, 280, 18).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.buy"), b -> buySelected())
                .dimensions(left + 12, top + 272, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Нанять: 1 день"), b -> send("hire", "", 1, 0))
                .dimensions(left + 120, top + 272, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Торг: снизить цену"), b -> send("bargain", "", 1, 10))
                .dimensions(left + 12, top + 250, 120, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.close"), b -> close())
                .dimensions(left + 240, top + 272, 108, 20).build());
    }

    private void buySelected() {
        if (stock.isEmpty() || selected >= stock.size()) return;
        StockEntry entry = stock.get(selected);
        send("buy", entry.id, 1, entry.price);
    }

    private void send(String action, String item, int amount, int offer) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(action);
        buf.writeUuid(trader);
        buf.writeString(item);
        buf.writeInt(amount);
        buf.writeInt(offer);
        ClientPlayNetworking.send(PillagerTradeMod.ACTION, buf);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int left = (width - 360) / 2;
        int top = (height - 300) / 2;
        context.fill(left, top, left + 360, top + 300, 0xFFC6C6C6);
        context.drawBorder(left, top, 360, 300, 0xFF555555);
        context.drawText(textRenderer, traderName, left + 12, top + 10, 0xFFFFFF, false);
        context.drawText(textRenderer, "Репутация: " + reputation + " | День мира: " + day,
                left + 12, top + 26, 0xFF404040, false);
        context.drawText(textRenderer, "Этап торговли: " + stage + " / 5",
                left + 12, top + 42, 0xFF5A4A2A, false);
        String progress = stage >= 5 ? "Достигнут максимальный этап"
                : "Для этапа " + (stage + 1) + ": день " + nextStageDay
                + " и репутация " + nextStageReputation;
        context.drawText(textRenderer, progress, left + 12, top + 57, 0xFF555555, false);
        context.drawText(textRenderer, "Специализация: " + specializationName(specialization),
                left + 12, top + 237, 0xFF5A4A2A, false);
        if (hired) {
            context.drawText(textRenderer, "Этот поборник уже нанят",
                    left + 140, top + 257, 0xFF267A26, false);
        }
        super.render(context, mouseX, mouseY, delta);
        context.drawItem(new ItemStack(Items.EMERALD), left + 268, top + 8);
        context.drawText(textRenderer, String.valueOf(emeraldBalance), left + 288, top + 13, 0xFF267A26, false);
        for (int i = 0; i < Math.min(8, stock.size()); i++) {
            int rowY = top + 74 + i * 19;
            context.drawItem(new ItemStack(Items.EMERALD), left + 300, rowY + 1);
            context.drawText(textRenderer, String.valueOf(stock.get(i).price), left + 320, rowY + 5, 0xFF267A26, false);
        }
    }

    public void setEmeraldBalance(int emeraldBalance) {
        this.emeraldBalance = emeraldBalance;
    }

    private String specializationName(int id) {
        return switch (id) {
            case 0 -> "Снабженец";
            case 1 -> "Оружейник";
            case 2 -> "Старатель";
            case 3 -> "Алхимик";
            case 4 -> "Механик";
            case 5 -> "Контрабандист";
            case 6 -> "Интендант";
            default -> "Контрабандист";
        };
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public record StockEntry(String id, String name, int price) {}
}
