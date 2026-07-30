package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

@AllArgsConstructor @Getter
public class AttackBlockEvent extends Event {
    private final BlockPos position;
    private final Direction direction;
}
