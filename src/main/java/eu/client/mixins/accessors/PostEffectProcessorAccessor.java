package eu.client.mixins.accessors;

import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(PostChain.class)
public interface PostEffectProcessorAccessor {
    @Accessor("passes")
    List<PostPass> getPasses();
}
