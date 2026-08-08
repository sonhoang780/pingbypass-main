package eu.client.modules.impl.movement;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.NumberSetting;

// All the actual work lives in LivingEntityMixin (euclient$fastClimbIgnore /
// euclient$fastClimbScalePositive / euclient$fastClimbScaleNegative) -- this module is just the
// setting. 0 == Ignore (NumberSetting.zeroIsIgnore -- see NumberButton): forces onClimbable()
// false for the local player, ported from example-addon-master's IgnoreClimb, so the block
// collides like normal terrain (no climb-assist velocity/auto-stick) instead of leaving the player
// frozen mid-climb. 1 == vanilla's own climb speed, untouched. Values in between scale vanilla's
// OWN handleOnClimbable() clamp constants (+-0.15F) directly via @ModifyConstant -- genuinely
// vanilla's climb physics running at a smaller cap, not a synthetic post-hoc
// setDeltaMovement() override after the fact.
@RegisterModule(name = "FastClimb", description = "Modifies your climbing speed on ladders, scaffolding, vines and other climbables.", category = Module.Category.MOVEMENT)
public class FastClimbModule extends Module {
    public NumberSetting speed = new NumberSetting("Speed", "Speed", "How much of vanilla's climb speed to use. Ignore walks past climbables like normal blocks, 1 is vanilla's normal speed.",
            new Setting.Visibility(), 1.0f, 0.0f, 1.0f, 0.1f, true);
}
