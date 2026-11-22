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
        this.backgroundWidth = 230;
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
                    }).dimensions(x + 8, y + 72 + i * 20, 112, 18).build());
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xEE242424);
        context.drawBorder(x, y, backgroundWidth, backgroundHeight, 0xFF8B7653);
        context.drawText(textRenderer, "Образец", x + 24, y + 20, 0xFFFFFF, false);
        context.drawText(textRenderer, "Добыча", x + 142, y + 5, 0xFFFFFF, false);
        context.drawText(textRenderer, "Поборники поблизости:", x + 8, y + 60, 0xE4C98A, false);
        if (handler.getTraders().isEmpty()) {
            context.drawText(textRenderer, "Нет поборников в радиусе 16 блоков", x + 8, y + 76, 0xFF7777, false);
        }
        if (handler.getTable().hasActiveOrder()) {
            context.drawText(textRenderer, "Заказ выполняется", x + 8, y + 177, 0xFFCC55, false);
        } else if (handler.getTable().isReady()) {
            context.drawText(textRenderer, "Заказ доставлен!", x + 8, y + 177, 0x55FF55, false);
        } else if (handler.getTable().isFailed()) {
            context.drawText(textRenderer, "Заказ не выполнен", x + 8, y + 177, 0xFF7777, false);
        } else {
            context.drawText(textRenderer, "Плата: цена предмета x 8", x + 8, y + 177, 0xBBBBBB, false);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }
}