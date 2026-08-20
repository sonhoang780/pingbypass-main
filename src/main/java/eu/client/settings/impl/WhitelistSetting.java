package eu.client.settings.impl;

import lombok.Getter;
import lombok.Setter;
import eu.client.settings.Setting;
import eu.client.utils.annotations.AllowedTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter @Setter
public class WhitelistSetting extends Setting {
    private final Type type;
    private final Set<Object> whitelist = new HashSet<>();

    public WhitelistSetting(String name, String description, Type type) {
        super(name, name, description, new Setting.Visibility());
        this.type = type;
    }

    public WhitelistSetting(String name, String tag, String description, Type type) {
        super(name, tag, description, new Setting.Visibility());
        this.type = type;
    }

    public WhitelistSetting(String name, String description, Setting.Visibility visibility, Type type) {
        super(name, name, description, visibility);
        this.type = type;
    }

    public WhitelistSetting(String name, String tag, String description, Setting.Visibility visibility, Type type) {
        super(name, tag, description, visibility);
        this.type = type;
    }

    @AllowedTypes({Item.class, Block.class, Potion.class})
    public void add(Object object) {
        whitelist.add(object);
    }

    @AllowedTypes({Item.class, Block.class, Potion.class})
    public void remove(Object id) {
        whitelist.remove(id);
    }

    @AllowedTypes({Item.class, Block.class, Potion.class})
    public boolean isWhitelistContains(Object object) {
        return whitelist.contains(object);
    }

    public List<String> getWhitelistIds() {
        return whitelist.stream().map(object -> {
            if (object instanceof Item item) {
                return BuiltInRegistries.ITEM.getKey(item).toString();
            } else if (object instanceof Block block) {
                return BuiltInRegistries.BLOCK.getKey(block).toString();
            } else if (object instanceof Potion potion) {
                return BuiltInRegistries.POTION.getKey(potion).toString();
            }
            return null;
        }).toList();
    }

    // POTIONS type: whitelist stores real Potion entries (e.g. turtle_master = SLOWNESS+RESISTANCE
    // combo, not a single MobEffect) -- matches what the brewing stand/vanilla search box calls a
    // "potion", not raw effect names. See AutoPotModule for how this is consumed.
    public List<Potion> getWhitelistedPotions() {
        return whitelist.stream().filter(o -> o instanceof Potion).map(o -> (Potion) o).toList();
    }

    public void clear() {
        whitelist.clear();
    }

    public enum Type {
        ITEMS, BLOCKS, POTIONS
    }
}
