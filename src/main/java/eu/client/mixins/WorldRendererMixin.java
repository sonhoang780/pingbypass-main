package eu.client.mixins;

import eu.client.EUClient;
import eu.client.modules.impl.visuals.BlockHighlightModule;
import eu.client.modules.impl.visuals.ShadersModule;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

// ponytail: renderEntity's old per-entity VertexConsumer-swap WrapOperation (RenderEntityEvent)
// is dropped -- same as the old ModelRenderer chams technique it served, entity geometry is
// submitted as high-level submitModel(...) descriptors now, not per-vertex VertexConsumer draws,
// so there's no per-entity buffer to swap anymore (see ChamsModule's EntityRenderState.outlineColor
// rewrite). hasBlindnessOrDarkness's fog-bypass moved into the FogEnvironment system together with
// BackgroundRendererMixin's still-deferred fog rearchitecture.
// PORT (26.2): LevelRenderer.renderLevel -> render (renamed + signature changed to
// (GraphicsResourceAllocator, DeltaTracker, boolean renderOutline, CameraRenderState, Matrix4fc,
// GpuBufferSlice, Vector4f, boolean shouldRenderSky), confirmed via real source) -- renderOutline
// stays ordinal 0 among boolean params (still the first boolean in the new param list).
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean renderOutline(boolean renderOutline) {
        if (EUClient.MODULE_MANAGER != null && EUClient.MODULE_MANAGER.getModule(BlockHighlightModule.class).isToggled()) {
            return false;
        }

        return renderOutline;
    }

    // PORT (26.2): REMOVED, not just disabled -- LevelRenderer.update/cullTerrain(Camera, Frustum,
    // boolean spectator) is genuinely gone (confirmed via javap on the real jar: zero update/cull/
    // frustum/spectator methods left on LevelRenderer). Found the real replacement via
    // Camera.extractRenderState (real source): `cameraState.smartCull = this.minecraft.smartCull;
    // if (player.isSpectator() && level.getBlockState(blockPos).isSolidRender()) smartCull =
    // false;` -- occlusion culling now reads a plain Minecraft.smartCull boolean field every frame,
    // no per-call mixin needed. FreecamModule.onEnable/onDisable already flips
    // `mc.smartCull = false/true` directly (predates this port) -- same effect as this old
    // `spectator` param, just via the field instead of a method arg. Nothing lost.

    // Chams/PopChams/ShadersModule Fill/Outline/Both -- vanilla's real entity-glow post-chain
    // ("minecraft:entity_outline", a proper edge-detection blur, not the flat single-color
    // silhouette a naive read of EntityRenderState.outlineColor suggests) is what actually turns
    // the captured silhouette into a thin line. This renderer generation bakes post-chain
    // uniforms into a GPU buffer at load time with no exposed runtime setter, so both Mode
    // (RenderMode) AND Opacity (FillOpacity) are baked per pre-file variant (see
    // resources/assets/euclient/post_effect/outline_*.json) and picked by priority (Shaders >
    // PopChams > Chams entities > Chams crystals, first one actually toggled) -- an approximation
    // of the original's real per-module independent rendering, not a rebuild of it.
    //
    // Previously swapped the Identifier vanilla's own getPostChain(...) call requested, letting
    // the chain run inside LevelRenderer's FrameGraph like vanilla's normal entity_outline does.
    // That processes the WORLD silhouette only -- the hand's outline vertices aren't even flushed
    // into the shared buffer until later (ItemInHandRendererMixin.euclient$flushHandOutline,
    // AFTER this frame's FrameGraph already ran). Running our chain a SECOND time in
    // GameRendererMixin.euclient$resolveOutline (after that flush) would double-process the
    // world part if this call site were still allowed through too -- return null here instead
    // whenever one of our modules is active, so ours is the only run, ever, per frame.
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"))
    private PostChain euclient$interceptEntityOutlineChain(ShaderManager instance, Identifier id, Set<Identifier> allowed) {
        if (id.equals(Identifier.withDefaultNamespace("entity_outline")) && ShadersModule.pickActiveOutlineChain() != null) {
            return null;
        }
        return instance.getPostChain(id, allowed);
    }
}
