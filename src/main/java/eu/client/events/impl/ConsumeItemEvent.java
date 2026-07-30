package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.world.item.ItemStack;

@Getter @AllArgsConstructor
public class ConsumeItemEvent extends Event {
    private final ItemStack stack;
}
