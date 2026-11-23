package ru.pillagertrade.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import ru.pillagertrade.PillagerTradeMod;\nimport net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import ru.pillagertrade.OrderTableScreenHandler;

public class PillagerTradeClient implements ClientModInitializer {
    private static String localizeItem(String key) {
        String translated = Text.translatable(key).getString();
        if (!translated.equals(key)) return translated;
        String path = key.substring(key.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        java.util.Map<String, String> words = java.util.Map.ofEntries(
                java.util.Map.entry("iron", "железный"), java.util.Map.entry("gold", "золотой"),
                java.util.Map.entry("copper", "медный"), java.util.Map.entry("tin", "оловянный"),
                java.util.Map.entry("silver", "серебряный"), java.util.Map.entry("steel", "стальной"),
                java.util.Map.entry("bronze", "бронзовый"), java.util.Map.entry("diamond", "алмазный"),
                java.util.Map.entry("netherite", "незеритовый"), java.util.Map.entry("wooden", "деревянный"),
                java.util.Map.entry("stone", "каменный"), java.util.Map.entry("sword", "меч"),
                java.util.Map.entry("axe", "топор"), java.util.Map.entry("pickaxe", "кирка"),
                java.util.Map.entry("shovel", "лопата"), java.util.Map.entry("hoe", "мотыга"),
                java.util.Map.entry("helmet", "шлем"), java.util.Map.entry("chestplate", "нагрудник"),
                java.util.Map.entry("leggings", "поножи"), java.util.Map.entry("boots", "ботинки"),
                java.util.Map.entry("ingot", "слиток"), java.util.Map.entry("nugget", "самородок"),
                java.util.Map.entry("ore", "руда"), java.util.Map.entry("raw", "необработанный"),
                java.util.Map.entry("block", "блок"), java.util.Map.entry("gem", "самоцвет"),
                java.util.Map.entry("crystal", "кристалл"), java.util.Map.entry("shard", "осколок"),
                java.util.Map.entry("dust", "пыль"), java.util.Map.entry("plate", "пластина"),
                java.util.Map.entry("gear", "шестерня"), java.util.Map.entry("machine", "механизм"),
                java.util.Map.entry("bow", "лук"), java.util.Map.entry("crossbow", "арбалет"),
                java.util.Map.entry("shield", "щит"), java.util.Map.entry("apple", "яблоко"),
                java.util.Map.entry("bread", "хлеб"), java.util.Map.entry("berry", "ягода"),
                java.util.Map.entry("berries", "ягоды"), java.util.Map.entry("potion", "зелье"),
                java.util.Map.entry("staff", "посох"), java.util.Map.entry("hammer", "молот"),
                java.util.Map.entry("spear", "копьё"), java.util.Map.entry("knife", "нож"),
                java.util.Map.entry("ring", "кольцо"), java.util.Map.entry("amulet", "амулет"),
                java.util.Map.entry("helmet", "шлем"), java.util.Map.entry("armor", "броня"),
                java.util.Map.entry("boots", "ботинки"), java.util.Map.entry("leggings", "поножи"),
                java.util.Map.entry("wrench", "ключ"), java.util.Map.entry("circuit", "схема"),
                java.util.Map.entry("motor", "двигатель"), java.util.Map.entry("battery", "батарея"),
                java.util.Map.entry("fuel", "топливо"), java.util.Map.entry("coal", "уголь"),
                java.util.Map.entry("leather", "кожа"), java.util.Map.entry("string", "нить"),
                java.util.Map.entry("plank", "доски"), java.util.Map.entry("log", "бревно"),
                java.util.Map.entry("stick", "палка"), java.util.Map.entry("torch", "факел"),
                java.util.Map.entry("food", "еда"), java.util.Map.entry("meat", "мясо"),
                java.util.Map.entry("fish", "рыба"), java.util.Map.entry("seed", "семена"),
                java.util.Map.entry("bucket", "ведро"), java.util.Map.entry("arrow", "стрела"),
                java.util.Map.entry("bolt", "болт"), java.util.Map.entry("crystal", "кристалл"),
                java.util.Map.entry("fragment", "фрагмент"), java.util.Map.entry("essence", "эссенция"),
                java.util.Map.entry("upgrade", "улучшение"), java.util.Map.entry("template", "шаблон")
        );
        StringBuilder result = new StringBuilder();
        for (String token : path.replace('_', ' ').split(" ")) {
            if (result.length() > 0) result.append(' ');
            result.append(words.getOrDefault(token, token));
        }
        return result.toString();
    }


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
                String itemId = buf.readString(128);
                String translationKey = buf.readString(256);
                String localized = localizeItem(translationKey);
                stock.add(new PillagerTradeScreen.StockEntry(itemId, localized, buf.readInt()));
            }
            boolean activeQuest = buf.readBoolean();
            String questItemId = buf.readString(128);
            String questTranslationKey = buf.readString(256);\n            String questItemName = localizeItem(questTranslationKey);
            int questAmount = buf.readInt();
            int questReward = buf.readInt();
            int questProgress = buf.readInt();
            client.execute(() -> client.setScreen(new PillagerTradeScreen(trader, name, reputation, specialization, hired, day, stage, nextStageDay, nextStageReputation, emeraldBalance, stock, activeQuest, questItemId, questItemName, questAmount, questReward, questProgress)));
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
