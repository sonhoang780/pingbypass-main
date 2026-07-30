package eu.client.events.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import eu.client.events.Event;
import net.minecraft.world.phys.Vec3;

@Getter @Setter @RequiredArgsConstructor
public class UpdateVelocityEvent extends Event {
    private final Vec3 movementInput;
    private final float speed;

    private Vec3 velocity;
}
