package eu.client.mixins.accessors;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// PostPass builds one GpuBuffer per custom uniform BLOCK declared in the post_effect JSON, once,
// in its constructor -- and exposes no setter for them afterwards. The map itself is a plain
// mutable HashMap, which is the only seam this renderer generation leaves for animating a
// post-chain uniform per frame (see EspShader.writeOutlineSettings).
@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("customUniforms")
    Map<String, GpuBuffer> euclient$getCustomUniforms();
}
