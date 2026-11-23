package ru.pillagertrade;

import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.text.Text;

public class BaronCaneItem extends SwordItem {
    public BaronCaneItem(ToolMaterial material, int attackDamage, float attackSpeed, Item.Settings settings) {
        super(material, attackDamage, attackSpeed, settings);
    }

    @Override
    public Text getName() {
        return Text.translatable("item.pillager_trade.baron_cane");
    }
}
