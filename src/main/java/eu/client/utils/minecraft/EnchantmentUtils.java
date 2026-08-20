package eu.client.utils.minecraft;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class EnchantmentUtils {
    public static int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantmentKey) {
        if (stack == null || stack.isEmpty()) return 0;

        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || enchantments.isEmpty()) return 0;

        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            var keyOptional = entry.getKey().unwrapKey();
            if (keyOptional.isPresent() && keyOptional.get().equals(enchantmentKey)) {
                return entry.getIntValue();
            }
        }

        return 0;
    }
}
