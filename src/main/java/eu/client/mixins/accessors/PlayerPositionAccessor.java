package eu.client.mixins.accessors;

import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PositionMoveRotation.class)
public interface PlayerPositionAccessor {
    @Accessor("yRot") @Mutable
    void setYaw(float yaw);

    @Accessor("xRot") @Mutable
    void setPitch(float pitch);
}
