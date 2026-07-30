package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.core.BlockPos;

@Getter @AllArgsConstructor
public class BreakBlockEvent extends Event {
    private final BlockPos pos;
}
