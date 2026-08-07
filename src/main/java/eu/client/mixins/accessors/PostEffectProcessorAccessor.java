package eu.client.mixins.accessors;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(PostChain.class)
public interface PostEffectProcessorAccessor {
    @Accessor("passes")
    List<PostPass> getPasses();

    // PostChain.process(target, alloc) keeps any internal target marked "persistent": true in
    // this map AFTER the call returns (getOrCreatePersistentTarget stores it here, only ever
    // cleared by PostChain.close()) -- the only way to reach a post-chain's blurred output as a
    // real RenderTarget for further use (wrapping as a texture to blit, in CozyGlowCapture's
    // case) instead of it only ever being composited back into the chain's own "main" binding.
    @Accessor("persistentTargets")
    Map<Identifier, RenderTarget> getPersistentTargets();
}
