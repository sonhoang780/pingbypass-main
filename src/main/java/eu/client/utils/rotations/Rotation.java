package eu.client.utils.rotations;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.entity.Entity;

// Shoreline's net.shoreline.client.impl.rotation.util.Rotation, trimmed to the pieces RotationManager
// actually uses (yaw/pitch + apply) -- module/priority/time are gone, arbitration is now via
// ClientRotationEvent subscriber priority (see RotationPriorities), not data carried on the Rotation.
@Getter @Setter
public class Rotation {
    private float yaw, pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public Rotation(Entity entity) {
        this(entity.getYRot(), entity.getXRot());
    }

    public void apply(Entity entity) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
    }
}
