package eu.client.mixins.accessors;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] euclient$getLayers();

    @Accessor("activeLayerCount")
    int euclient$getActiveLayerCount();
}
