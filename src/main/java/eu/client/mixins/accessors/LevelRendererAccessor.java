package eu.client.mixins.accessors;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The public LevelRenderer.entityOutlineTarget() reads targets.entityOutline, a FrameGraph
// ResourceHandle that LevelRenderer.renderLevel() clears (targets.clear()) before returning -- so
// it's null for the entire hand-render pass (GameRenderer.renderItemInHand runs strictly after
// renderLevel). The private field itself stays populated (LevelRenderer.initOutline() runs once,
// unconditionally, from onResourceManagerReload -- never call it again, it destroys+recreates the
// target). This accessor reaches the real target regardless of the FrameGraph's per-pass state.
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("entityOutlineTarget")
    RenderTarget euclient$getEntityOutlineTarget();
}
