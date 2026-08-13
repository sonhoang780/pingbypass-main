package eu.client.utils.rotations;

import lombok.Getter;
import lombok.Setter;
import eu.client.modules.Module;

// bản gốc 1.21.4's Rotation.java, ported verbatim -- used only by RotationManager's
// legacyRotate()/legacyQueue (SpeedMine/AutoCrystal's "Normal" rotate mode), kept separate from
// the trimmed Rotation class the ClientRotationEvent system uses so neither is disturbed by the
// other.
@Getter @Setter
public class LegacyRotation {
    private float yaw, pitch;
    private final Module module;
    private final int priority;
    private long time;

    public LegacyRotation(float yaw, float pitch, int priority) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.module = null;
        this.priority = priority;
        this.time = System.currentTimeMillis();
    }

    public LegacyRotation(float yaw, float pitch, Module module, int priority) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.module = module;
        this.priority = priority;
        this.time = System.currentTimeMillis();
    }
}
