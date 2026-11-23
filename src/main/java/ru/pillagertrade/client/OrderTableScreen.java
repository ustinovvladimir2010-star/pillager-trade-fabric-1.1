package ru.pillagertrade.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import ru.pillagertrade.OrderTableScreenHandler;
import ru.pillagertrade.PillagerTradeMod;

public class OrderTableScreen extends HandledScreen<OrderTableScreenHandler> {
    public OrderTableScreen(OrderTableScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 330;
        this.backgroundHeight = 210;
        this.playerInventoryTitleY = 116;
        this.titleY = 6;
    }

    @Override
    protected void init() {
        super.init();
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        int shown = Math.min(5, handler.getTraders().size());
        for (int i = 0; i < shown; i++) {
            final int index = i;
            OrderTableScreenHandler.TraderOption trader = handler.getTraders().get(i);
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(trader.name() + " | реп. " + trader.reputation()),
                    button -> {
                        PacketByteBuf buf = PacketByteBufs.create();
                        buf.writeBlockPos(handler.getTable().getPos());
                        buf.writeVarInt(index);
                        ClientPlayNetworking.send(PillagerTradeMod.ORDER_START, buf);
                    }).dimensions(x + 174, y + 34 + i * 22, 148, 20).build());
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFFC6C6C6);
        context.drawBorder(x, y, backgroundWidth, backgroundHeight, 0xFF555555);

        // Light vanilla-style work areas behind the sample and delivery slots.
        context.fill(x + 39, y + 30, x + 67, y + 58, 0xFFE2E2E2);
        context.drawBorder(x + 39, y + 30, 28, 28, 0xFF777777);
        context.fill(x + 111, y + 12, x + 169, y + 70, 0xFFE2E2E2);
        context.drawBorder(x + 111, y + 12, 58, 58, 0xFF777777);
        context.drawText(textRenderer, "Образец", x + 24, y + 20, 0xFF404040, false);
        context.drawText(textRenderer, "Добыча", x + 142, y + 5, 0xFF404040, false);
        context.drawText(textRenderer, "Поборники поблизости:", x + 174, y + 18, 0xFF404040, false);
        if (handler.getTraders().isEmpty()) {
            context.drawText(textRenderer, "Нет поборников в радиусе 16 блоков", x + 174, y + 38, 0xFFAA3333, false);
        }
        if (handler.getTable().hasActiveOrder()) {
            context.drawText(textRenderer, "Заказ выполняется", x + 174, y + 164, 0xFF8A6500, false);
        } else if (handler.getTable().isReady()) {
            context.drawText(textRenderer, "Заказ доставлен!", x + 174, y + 164, 0xFF267A26, false);
        } else if (handler.getTable().isFailed()) {
            context.drawText(textRenderer, "Заказ не выполнен", x + 174, y + 164, 0xFFAA3333, false);
        } else {
            context.drawText(textRenderer, "Плата: цена предмета x 8", x + 174, y + 164, 0xFF555555, false);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }
}