package eu.client.mixins.accessors;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Invoker("getInputVector")
    static Vec3 invokeMovementInputToVelocity(Vec3 movementInput, float speed, float yaw) {
        throw new AssertionError();
    }
}
