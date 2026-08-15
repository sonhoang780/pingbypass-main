package eu.client.modules.impl.core;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;

// Restored 2026-08-14 -- bản gốc 1.21.4 has this module (same name, same two settings, same
// defaults) and the port had deleted it outright, taking BOTH features with it. That left 26.1.2
// with no movement fix of any kind while a silent rotation is held: the port's own replacement
// (RotationManager.computeMoveFix's octant remap) was itself deleted the same day for being an
// always-on rewrite of the real WASD input that bản gốc never had. Deleting the always-on version
// was correct; deleting the ORIGINAL's opt-in version along with it was not -- "Makes your movement
// in accordance with your yaw" is exactly the knob for the reported "Rotate=Normal + AutoCrystal =
// flagged, can't move" symptom, and it exists in the version being compared against.
//
// Verbatim from bản gốc, defaults included (both false).
@RegisterModule(name = "Rotations", description = "Manages the client's rotation system.", category = Module.Category.CORE, persistent = true, drawn = false)
public class RotationsModule extends Module {
    public BooleanSetting movementFix = new BooleanSetting("MovementFix", "Makes your movement in accordance with your yaw.", false);
    public BooleanSetting snapBack = new BooleanSetting("SnapBack", "Reverts rotations to previous values after rotating.", false);

    // 2026-08-15, ported from NamiDevelopment/nami-public's Rotations feature (JitterMode enum in
    // RotationsFeatureConfig, applied in RotationRequestHandler.performSilent() and
    // RotationTickHandler.interpolateRotation()). Adds noise to every fake rotation this project
    // sends so repeated packets are never bit-for-bit identical -- Grim's aim/rotation checks
    // (see AimModulo360) flag a yaw/pitch that holds EXACTLY still across many packets as
    // characteristic of an aimbot, since a real human's aim always drifts by some tiny amount.
    // "Grim" mirrors Nami's own GRIM mode (+-0.001 degrees on pitch only, invisible on screen,
    // sized to the exact tolerance GrimAC's own check ignores); "Normal" mirrors their NORMAL mode
    // (both yaw and pitch jittered by rotationThreshold/4..rotationThreshold/2, i.e. scaled to
    // this project's own RotationManager threshold rather than a hardcoded constant).
    public ModeSetting jitter = new ModeSetting("Jitter", "Adds random noise to sent rotations so they're never bit-for-bit identical, defeating aim-consistency checks like Grim's AimModulo360.", "None", new String[]{"None", "Grim", "Normal"});
}
