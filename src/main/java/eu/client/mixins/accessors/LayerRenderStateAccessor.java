package eu.client.mixins.accessors;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("quads")
    List<BakedQuad> euclient$getQuads();

    @Accessor("foilType")
    ItemStackRenderState.FoilType euclient$getFoilType();

    @Accessor("tintLayers")
    IntList euclient$getTintLayers();

    @Invoker("applyTransform")
    void euclient$applyTransform(PoseStack.Pose pose);
}
