package eu.client.mixins.accessors;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("frustum")
    Frustum getFrustum();

    @Accessor("entityOutlineFramebuffer")
    RenderTarget getEntityOutlineFramebuffer();

    @Accessor("entityOutlineFramebuffer")
    void setEntityOutlineFramebuffer(RenderTarget framebuffer);
}
