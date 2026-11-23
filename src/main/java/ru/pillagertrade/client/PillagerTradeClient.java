package ru.pillagertrade.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import ru.pillagertrade.PillagerTradeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import ru.pillagertrade.OrderTableScreenHandler;

public class PillagerTradeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(PillagerTradeMod.ORDER_TABLE_SCREEN, OrderTableScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(PillagerTradeMod.OPEN_SCREEN, (client, handler, buf, responseSender) -> {
            UUID trader = buf.readUuid();
            String name = buf.readString(128);
            int reputation = buf.readInt();
            int specialization = buf.readInt();
            boolean hired = buf.readBoolean();
            long day = buf.readLong();
            int stage = buf.readInt();
            int nextStageDay = buf.readInt();
            int nextStageReputation = buf.readInt();
            int emeraldBalance = buf.readInt();
            int count = buf.readVarInt();
            List<PillagerTradeScreen.StockEntry> stock = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                stock.add(new PillagerTradeScreen.StockEntry(buf.readString(128), buf.readString(128), buf.readInt()));
            }
            boolean activeQuest = buf.readBoolean();
            String questItemId = buf.readString(128);
            String questItemName = buf.readString(128);
            int questAmount = buf.readInt();
            int questReward = buf.readInt();
            client.execute(() -> client.setScreen(new PillagerTradeScreen(trader, name, reputation, specialization, hired, day, stage, nextStageDay, nextStageReputation, emeraldBalance, stock, activeQuest, questItemId, questItemName, questAmount, questReward)));
        });

        ClientPlayNetworking.registerGlobalReceiver(PillagerTradeMod.SYNC, (client, handler, buf, responseSender) -> {
            int emeraldBalance = buf.readInt();
            client.execute(() -> {
                if (client.currentScreen instanceof PillagerTradeScreen screen) {
                    screen.setEmeraldBalance(emeraldBalance);
                }
            });
        });
    }
}
