package eu.client.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.client.EUClient;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// ponytail: DeltaTracker.Dynamic (with its beginRenderTick/prevTimeMillis fields) is gone, replaced
// by DeltaTracker.Timer.advanceGameTime(long), which derives deltaTicks from elapsed real time divided
// by the target ms-per-tick. Scaling that divisor by the timer multiplier has the same net effect as
// the old lastFrameDuration scaling: a smaller divisor -> more deltaTicks per real millisecond ->
// perceived-faster game ticks, which is what TickShift/StepModule etc. actually want.
@Mixin(DeltaTracker.Timer.class)
public class RenderTickCounterDynamicMixin {
    @ModifyExpressionValue(method = "advanceGameTime", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F"))
    private float euclient$scaleMspt(float mspt) {
        return mspt / EUClient.WORLD_MANAGER.getTimerMultiplier();
    }
}
