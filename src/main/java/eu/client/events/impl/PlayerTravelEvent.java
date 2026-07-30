package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.world.phys.Vec3;

@Getter @AllArgsConstructor
public class PlayerTravelEvent extends Event {
    private final Vec3 movementInput;
}
