package eu.client.mixins.accessors;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private world-space render snippets used to build our own no-depth-test variants. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("LINES_SNIPPET")
    static RenderPipeline.Snippet getLinesSnippet() {
        throw new AssertionError();
    }

    @Accessor("DEBUG_FILLED_SNIPPET")
    static RenderPipeline.Snippet getDebugFilledSnippet() {
        throw new AssertionError();
    }

    @Accessor("ITEM_SNIPPET")
    static RenderPipeline.Snippet getItemSnippet() {
        throw new AssertionError();
    }
}
