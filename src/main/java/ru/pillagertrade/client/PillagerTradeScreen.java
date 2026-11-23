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
    private boolean questsTab = false;
    private final boolean activeQuest;
    private final String questItemId;
    private final String questItemName;
    private final int questAmount;
    private final int questReward;
    private final List<ButtonWidget> tradeButtons = new java.util.ArrayList<>();
    private ButtonWidget questActionButton;

    public PillagerTradeScreen(UUID trader, String name, int reputation, int specialization,
                               boolean hired, long day, int stage, int nextStageDay,
                               int nextStageReputation, int emeraldBalance, List<StockEntry> stock,
                               boolean activeQuest, String questItemId, String questItemName, int questAmount, int questReward) {
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
        this.activeQuest = activeQuest;
        this.questItemId = questItemId;
        this.questItemName = questItemName;
        this.questAmount = questAmount;
        this.questReward = questReward;
    }

    @Override
    protected void init() {
        int left = (width - 360) / 2;
        int top = (height - 300) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Торговля"), b -> setTab(false))
                .dimensions(left + 12, top + 8, 88, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Задания"), b -> setTab(true))
                .dimensions(left + 104, top + 8, 88, 20).build());
        for (int i = 0; i < Math.min(8, stock.size()); i++) {
            final int index = i;
            ButtonWidget button = ButtonWidget.builder(Text.literal(stock.get(i).name), b -> selected = index)
                    .dimensions(left + 12, top + 74 + i * 19, 280, 18).build();
            tradeButtons.add(addDrawableChild(button));
        }
        tradeButtons.add(addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.buy"), b -> buySelected())
                .dimensions(left + 12, top + 272, 100, 20).build()));
        tradeButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal("Нанять: 1 день"), b -> send("hire", "", 1, 0))
                .dimensions(left + 120, top + 272, 112, 20).build()));
        tradeButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal("Торг: +10% скидки"), b -> send("bargain", "", 1, 10))
                .dimensions(left + 12, top + 250, 120, 20).build()));
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.pillager_trade.close"), b -> close())
                .dimensions(left + 240, top + 272, 108, 20).build());
        questActionButton = addDrawableChild(ButtonWidget.builder(Text.literal(activeQuest ? "Сдать предметы" : "Принять задание"),
                b -> {
                    send(activeQuest ? "quest_complete" : "quest_accept", questItemId, questAmount, questReward);
                    close();
                })
                .dimensions(left + 12, top + 220, 150, 22).build());
        setTab(false);
    }

    private void setTab(boolean quests) {
        questsTab = quests;
        for (ButtonWidget button : tradeButtons) button.visible = !quests;
        if (questActionButton != null) questActionButton.visible = quests;
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
        context.fill(left, top, left + 360, top + 300, 0xFF242424);
        context.drawBorder(left, top, 360, 300, 0xFF111111);
        context.drawText(textRenderer, traderName, left + 204, top + 13, 0xFFFFFFFF, false);
        if (questsTab) {
            context.drawText(textRenderer, "ЗАДАНИЯ ТОРГОВЦА", left + 12, top + 48, 0xFFE0C080, false);
            if (activeQuest) {
                context.drawText(textRenderer, "Активное задание: найдите предметы", left + 12, top + 80, 0xFFE0E0E0, false);
                context.drawText(textRenderer, questAmount + " x " + questItemName, left + 12, top + 104, 0xFFFFFFFF, false);
                context.drawText(textRenderer, "Награда: " + questReward + " изумрудов", left + 12, top + 128, 0xFF70D080, false);
                context.drawText(textRenderer, "Другие задания откроются после сдачи.", left + 12, top + 158, 0xFFB0B0B0, false);
            } else {
                context.drawText(textRenderer, "Поиск предметов:", left + 12, top + 80, 0xFFE0E0E0, false);
                context.drawText(textRenderer, questAmount + " x " + questItemName, left + 12, top + 104, 0xFFFFFFFF, false);
                context.drawText(textRenderer, "Награда: " + questReward + " изумрудов", left + 12, top + 128, 0xFF70D080, false);
                context.drawText(textRenderer, "Одновременно можно выполнять только одно задание.", left + 12, top + 158, 0xFFB0B0B0, false);
            }
        } else {
            context.drawText(textRenderer, "Репутация: " + reputation + " | День мира: " + day,
                    left + 12, top + 26, 0xFFE0E0E0, false);
            context.drawText(textRenderer, "Этап торговли: " + stage + " / 5",
                    left + 12, top + 42, 0xFFC5A66A, false);
            String progress = stage >= 5 ? "Достигнут максимальный этап"
                    : "Для этапа " + (stage + 1) + ": день " + nextStageDay
                    + " и репутация " + nextStageReputation;
            context.drawText(textRenderer, progress, left + 12, top + 57, 0xFFB0B0B0, false);
            context.drawText(textRenderer, "Специализация: " + specializationName(specialization),
                    left + 12, top + 237, 0xFFC5A66A, false);
            if (hired) context.drawText(textRenderer, "Этот поборник уже нанят",
                    left + 140, top + 257, 0xFF70D080, false);
        }
        super.render(context, mouseX, mouseY, delta);
        drawDarkButton(context, left + 12, top + 8, 88, 20, "Торговля", mouseX, mouseY);
        drawDarkButton(context, left + 104, top + 8, 88, 20, "Задания", mouseX, mouseY);
        drawDarkButton(context, left + 240, top + 272, 108, 20, "Закрыть", mouseX, mouseY);
        if (questsTab) {
            drawDarkButton(context, left + 12, top + 220, 150, 22,
                    activeQuest ? "Сдать предметы" : "Принять задание", mouseX, mouseY);
        } else {
            for (int i = 0; i < Math.min(8, stock.size()); i++)
                drawDarkButton(context, left + 12, top + 74 + i * 19, 280, 18, stock.get(i).name, mouseX, mouseY);
            drawDarkButton(context, left + 12, top + 250, 120, 20, "Торг: +10% скидки", mouseX, mouseY);
            drawDarkButton(context, left + 12, top + 272, 100, 20, "Купить", mouseX, mouseY);
            drawDarkButton(context, left + 120, top + 272, 112, 20, "Нанять: 1 день", mouseX, mouseY);
        }
        if (!questsTab) {
            context.drawItem(new ItemStack(Items.EMERALD), left + 268, top + 8);
            context.drawText(textRenderer, String.valueOf(emeraldBalance), left + 288, top + 13, 0xFF70D080, false);
            for (int i = 0; i < Math.min(8, stock.size()); i++) {
                int rowY = top + 74 + i * 19;
                context.drawItem(new ItemStack(Items.EMERALD), left + 300, rowY + 1);
                context.drawText(textRenderer, String.valueOf(stock.get(i).price), left + 320, rowY + 5, 0xFF70D080, false);
            }
        }
    }

    private void drawDarkButton(DrawContext context, int x, int y, int w, int h, String label, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        context.fill(x, y, x + w, y + h, hovered ? 0xFF555555 : 0xFF3A3A3A);
        context.drawBorder(x, y, w, h, 0xFF777777);
        int textX = x + (w - textRenderer.getWidth(label)) / 2;
        context.drawText(textRenderer, label, textX, y + (h - 8) / 2, 0xFFFFFFFF, false);
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
