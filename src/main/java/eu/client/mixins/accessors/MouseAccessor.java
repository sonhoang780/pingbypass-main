package eu.client.mixins.accessors;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface MouseAccessor {
    @Invoker("onButton")
    void invokeOnButton(long handle, MouseButtonInfo rawButtonInfo, int action);
}
