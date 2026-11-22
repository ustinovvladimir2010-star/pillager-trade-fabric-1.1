package ru.pillagertrade.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.text.Text;
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
    private final List<StockEntry> stock;
    private int selected = 0;

    public PillagerTradeScreen(UUID trader, String name, int reputation, int specialization,
                               boolean hired, long day, int stage, List<StockEntry> stock) {
        super(Text.translatable("screen.pillager_trade.title"));
        this.trader = trader;
        this.traderName = name;
        this.reputation = reputation;
        this.specialization = specialization;
        this.hired = hired;
        this.day = day;
        this.stage = stage;
        this.stock = stock;
    }

    @Override
    protected void init() {
        int left = (width - 360) / 2;
        int top = (height - 280) / 2;

        for (int i = 0; i < Math.min(8, stock.size()); i++) {
            final int index = i;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(stock.get(i).name + " — " + stock.get(i).price + " э"),
                    b -> selected = index)
                    .dimensions(left + 12, top + 58 + i * 19, 336, 18).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.buy"), b -> buySelected())
                .dimensions(left + 12, top + 246, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Нанять: 1 день"), b -> send("hire", "", 1, 0))
                .dimensions(left + 120, top + 246, 112, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.close"), b -> close())
                .dimensions(left + 240, top + 246, 108, 20).build());
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
        int top = (height - 280) / 2;
        context.fill(left, top, left + 360, top + 280, 0xEE242424);
        context.drawBorder(left, top, 360, 280, 0xFF8B7653);
        context.drawText(textRenderer, traderName, left + 12, top + 10, 0xFFFFFF, false);
        context.drawText(textRenderer, "Репутация: " + reputation + " | День мира: " + day,
                left + 12, top + 26, 0xBFBFBF, false);
        context.drawText(textRenderer, "Этап торговли: " + stage + " / 5",
                left + 12, top + 42, 0xE4C98A, false);
        context.drawText(textRenderer, "Специализация: " + specializationName(specialization),
                left + 12, top + 216, 0xD8C89A, false);
        if (hired) {
            context.drawText(textRenderer, "Этот поборник уже нанят",
                    left + 12, top + 230, 0x55FF55, false);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private String specializationName(int id) {
        return switch (id) {
            case 0 -> "Снабженец";
            case 1 -> "Оружейник";
            case 2 -> "Старатель";
            case 3 -> "Алхимик";
            case 4 -> "Механик";
            default -> "Контрабандист";
        };
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public record StockEntry(String id, String name, int price) {}
}
