package eu.client.utils.minecraft;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

public class IdentifierUtils {
    public static Item getItem(String name) {
        try {
            Item item = getIdentifier(BuiltInRegistries.ITEM, name);
            if (item != null) return item;
        } catch (Exception ignored) {}
        return null;
    }

    public static Block getBlock(String name) {
        try {
            Block block = getIdentifier(BuiltInRegistries.BLOCK, name);
            if (block != null) return block;
        } catch (Exception ignored) {}
        return null;
    }

    public static Potion getPotion(String name) {
        try {
            Potion potion = getIdentifier(BuiltInRegistries.POTION, name);
            if (potion != null) return potion;
        } catch (Exception ignored) {}
        return null;
    }

    public static <T> T getIdentifier(Registry<T> registry, String name) {
        name = name.trim();

        Identifier identifier;
        if (name.contains(":")) {
            identifier = Identifier.parse(name);
        } else {
            identifier = Identifier.fromNamespaceAndPath("minecraft", name);
        }

        if (registry.containsKey(identifier)) return registry.getValue(identifier);
        return null;
    }
}
