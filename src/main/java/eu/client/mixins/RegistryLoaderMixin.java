package eu.client.mixins;

import eu.client.pingbypass.PingBypassFlags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * Safety net mixin — clears registry loading errors when PingBypass flag is set.
 * Should not be needed with proper RegistryCache, but kept as a fallback.
 */
@Mixin(RegistryDataLoader.class)
public class RegistryLoaderMixin {

    @ModifyVariable(
        method = "load",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;isEmpty()Z"
        ),
        ordinal = 0
    )
    private static Map<ResourceKey<?>, Exception> euclient$clearErrors(Map<ResourceKey<?>, Exception> errors) {
        if (PingBypassFlags.tolerateRegistryErrors && !errors.isEmpty()) {
            PingBypassFlags.tolerateRegistryErrors = false;
            errors.clear();
        }
        return errors;
    }
}
