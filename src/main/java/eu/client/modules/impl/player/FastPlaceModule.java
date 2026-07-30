package eu.client.modules.impl.player;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

@RegisterModule(name = "FastPlace", description = "Allows you to customize the tick delay between using items.", category = Module.Category.PLAYER)
public class FastPlaceModule extends Module {
    public NumberSetting ticks = new NumberSetting("Ticks", "The amount of ticks that have to be waited for before using items again.", 1, 0, 20);

    public BooleanSetting ignoreBlocks = new BooleanSetting("IgnoreBlocks", "Uses the default Minecraft delay when holding a block item.", true);
    public BooleanSetting ignoreFireworks = new BooleanSetting("IgnoreFireworks", "Uses the default Minecraft delay when holding fireworks.", true);
    public BooleanSetting ignorePearls = new BooleanSetting("IgnorePearls", "Uses the default Minecraft delay when holding pearls.", true);
    public BooleanSetting ignoreEquipment = new BooleanSetting("IgnoreEquipment", "Uses the default Minecraft delay when holding an equipment item.", true);

    @Override
    public String getMetaData() {
        return String.valueOf(ticks.getValue().intValue());
    }

    public boolean isValidItem(Item item) {
        if (ignoreBlocks.getValue() && item instanceof BlockItem) return false;
        if (ignoreFireworks.getValue() && item == Items.FIREWORK_ROCKET) return false;
        if (ignorePearls.getValue() && item == Items.ENDER_PEARL) return false;
        boolean isArmor = item.builtInRegistryHolder().is(ItemTags.FOOT_ARMOR) || item.builtInRegistryHolder().is(ItemTags.LEG_ARMOR) || item.builtInRegistryHolder().is(ItemTags.CHEST_ARMOR) || item.builtInRegistryHolder().is(ItemTags.HEAD_ARMOR);
        return !ignoreEquipment.getValue() || (!isArmor && item != Items.ELYTRA);
    }
}
