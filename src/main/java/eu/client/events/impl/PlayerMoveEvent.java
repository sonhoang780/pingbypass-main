package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import eu.client.events.Event;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

@AllArgsConstructor @Getter @Setter
public class PlayerMoveEvent extends Event {
    private final MoverType movementType;
    private Vec3 movement;

    public double getX() {
        return movement.x();
    }

    public void setX(double x) {
        this.movement = new Vec3(x, movement.y(), movement.z());
    }

    public double getY() {
        return movement.y();
    }

    public void setY(double y) {
        this.movement = new Vec3(movement.x(), y, movement.z());
    }

    public double getZ() {
        return movement.z();
    }

    public void setZ(double z) {
        this.movement = new Vec3(movement.x(), movement.y(), z);
    }
}