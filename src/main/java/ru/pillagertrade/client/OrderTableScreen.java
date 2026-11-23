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
    private boolean summonTab = false;
    private final java.util.List<ButtonWidget> traderButtons = new java.util.ArrayList<>();
    private ButtonWidget summonButton;
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
        traderButtons.clear();
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Доставка"), b -> setTab(false))
                .dimensions(x + 174, y + 10, 72, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Призыв"), b -> setTab(true))
                .dimensions(x + 250, y + 10, 72, 20).build());
        int shown = Math.min(5, handler.getTraders().size());
        for (int i = 0; i < shown; i++) {
            final int index = i;
            OrderTableScreenHandler.TraderOption trader = handler.getTraders().get(i);
            ButtonWidget button = ButtonWidget.builder(
                    Text.literal(trader.name() + " | реп. " + trader.reputation() + " | ур. " + trader.strength()),
                    b -> {
                        PacketByteBuf buf = PacketByteBufs.create();
                        buf.writeBlockPos(handler.getTable().getPos());
                        buf.writeVarInt(index);
                        ClientPlayNetworking.send(PillagerTradeMod.ORDER_START, buf);
                    }).dimensions(x + 174, y + 34 + i * 22, 148, 20).build();
            traderButtons.add(addDrawableChild(button));
        }
        summonButton = addDrawableChild(ButtonWidget.builder(Text.literal("Заказать подкрепление • 24 изумруда"), button -> {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(handler.getTable().getPos());
            ClientPlayNetworking.send(new net.minecraft.util.Identifier(PillagerTradeMod.MOD_ID, "summon_order"), buf);
        }).dimensions(x + 174, y + 54, 148, 22).build());
        setTab(summonTab);
    }

    private void setTab(boolean summon) {
        summonTab = summon;
        for (ButtonWidget button : traderButtons) button.visible = !summon;
        if (summonButton != null) summonButton.visible = summon;
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
        if (!summonTab) context.drawText(textRenderer, "Выберите торговца для доставки:", x + 174, y + 18, 0xFF404040, false);
        if (!summonTab && handler.getTable().hasActiveOrder() && !handler.getTable().isSummonOrder()) {
            context.drawText(textRenderer, "Шанс доставки: " + handler.getTable().getDeliveryChancePercent() + "%", x + 174, y + 132, 0xFF267A26, false);
        }
        else context.drawText(textRenderer, "Подкрепление из 1–3 разбойников:", x + 174, y + 34, 0xFF404040, false);
        if (!summonTab && handler.getTraders().isEmpty()) {
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