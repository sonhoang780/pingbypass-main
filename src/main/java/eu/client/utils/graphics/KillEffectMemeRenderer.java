package eu.client.utils.graphics;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eu.client.mixins.accessors.RenderPipelinesAccessor;
import eu.client.utils.IMinecraft;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * DeathEffects' Meme mode -- ported from example-addon-master's KillEffectMemeRenderer (Fabric's
 * LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN swapped for this project's own RenderWorldEvent,
 * same technique otherwise: a red circle centered on the death position, blinking on/off 3 times,
 * with a red curved arrow -- fixed camera-relative offset, same billboard basis as the circle --
 * pointing into it. Both are camera-facing billboards, same shape as ESP glow dots. Binary blink
 * (no fade); DeathEffectsModule plays the accompanying "vine" sound once at spawn time.
 */
public class KillEffectMemeRenderer implements IMinecraft {
    public static final KillEffectMemeRenderer INSTANCE = new KillEffectMemeRenderer();

    private static final Identifier CIRCLE_TEXTURE_ID = Identifier.fromNamespaceAndPath("euclient", "textures/effect/meme_circle.png");
    private static final Identifier ARROW_TEXTURE_ID = Identifier.fromNamespaceAndPath("euclient", "textures/effect/meme_arrow.png");
    private static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("euclient", "core/killeffect_meme");

    private static final int BLINK_COUNT = 3;
    private static final float BLINK_ON_SEC = 0.18f;
    private static final float BLINK_OFF_SEC = 0.12f;
    private static final float CYCLE_SEC = BLINK_ON_SEC + BLINK_OFF_SEC;

    private static RenderType circleLayer;
    private static RenderType arrowLayer;

    private static final class MemeInstance {
        final Vector3f pos = new Vector3f(); // world-absolute, where the circle centers
        float age;
        float size;
    }

    private final List<MemeInstance> instances = new ArrayList<>();
    private long lastNanos = 0L;

    /** Fire one blinking circle+arrow at {@code worldPos}. Called once per death (Meme mode). */
    public void spawn(Vector3f worldPos, float size) {
        MemeInstance inst = new MemeInstance();
        inst.pos.set(worldPos);
        inst.size = size;
        instances.add(inst);
    }

    /** @param matrices camera-relative world matrices, same PoseStack RenderWorldEvent already hands every module. */
    public void render(PoseStack matrices) {
        if (instances.isEmpty()) { lastNanos = 0L; return; }
        if (mc.player == null || mc.level == null) return;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min((now - lastNanos) / 1.0e9f, 0.1f);
        lastNanos = now;

        float totalLife = BLINK_COUNT * CYCLE_SEC;
        Iterator<MemeInstance> it = instances.iterator();
        while (it.hasNext()) {
            MemeInstance inst = it.next();
            inst.age += dt;
            if (inst.age >= totalLife) it.remove();
        }
        if (instances.isEmpty()) return;

        PoseStack.Pose pose = matrices.last();
        Camera camera = mc.gameRenderer.mainCamera();
        Vector3f camUp = new Vector3f(camera.upVector());
        Vector3f camLeft = new Vector3f(camera.leftVector());
        var camPosD = camera.position();
        Vector3f camPos = new Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        // PORT (26.2): MultiBufferSource removed -- own a growable ByteBufferBuilder per RenderType
        // (instance count varies per frame, not known upfront) and draw immediately, same
        // immediate-mode pattern as Renderer3D.draw/FontManager.drawTextWithShadow (see their PORT
        // comments). Two SEQUENTIAL passes, each its own buffer -- see KillEffectParticleSystem's
        // own doc (ported alongside this) for why interleaving two buffers crashes.
        Vector3f rel = new Vector3f();
        RenderType circleType = getCircleLayer();
        try (ByteBufferBuilder circleBytes = new ByteBufferBuilder(1024)) {
            BufferBuilder circleVc = new BufferBuilder(circleBytes, circleType.primitiveTopology(), circleType.format());
            for (MemeInstance inst : instances) {
                if (inst.age % CYCLE_SEC >= BLINK_ON_SEC) continue; // off phase of the blink
                rel.set(inst.pos).sub(camPos);
                emitBillboard(circleVc, pose, rel, camUp, camLeft, inst.size, 255);
            }
            MeshData mesh = circleVc.build();
            Renderer3D.draw(circleType, mesh);
        }

        Vector3f arrowRel = new Vector3f();
        RenderType arrowType = getArrowLayer();
        try (ByteBufferBuilder arrowBytes = new ByteBufferBuilder(1024)) {
            BufferBuilder arrowVc = new BufferBuilder(arrowBytes, arrowType.primitiveTopology(), arrowType.format());
            for (MemeInstance inst : instances) {
                if (inst.age % CYCLE_SEC >= BLINK_ON_SEC) continue;
                rel.set(inst.pos).sub(camPos);
                // Arrow sits up-and-right of the circle, same billboard basis, smaller and
                // fixed-offset -- its own baked-in shape already points down-left into the circle,
                // so no extra rotation is needed to "aim" it.
                arrowRel.set(camLeft).mul(-inst.size * 1.1f)
                        .add(camUp.x * inst.size * 1.1f, camUp.y * inst.size * 1.1f, camUp.z * inst.size * 1.1f)
                        .add(rel);
                emitBillboard(arrowVc, pose, arrowRel, camUp, camLeft, inst.size * 0.8f, 255);
            }
            MeshData mesh = arrowVc.build();
            Renderer3D.draw(arrowType, mesh);
        }
    }

    /** Camera-facing textured quad, UV 0..1 across the whole image. */
    private static void emitBillboard(VertexConsumer vc, PoseStack.Pose pose, Vector3f c, Vector3f camUp, Vector3f camLeft, float size, int alpha) {
        Vector3f u = new Vector3f(camUp).mul(size);
        Vector3f l = new Vector3f(camLeft).mul(size);
        Vector3f v0 = new Vector3f(c).sub(u).sub(l);
        Vector3f v1 = new Vector3f(c).sub(u).add(l);
        Vector3f v2 = new Vector3f(c).add(u).add(l);
        Vector3f v3 = new Vector3f(c).add(u).sub(l);
        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        // U swapped vs the "obvious" mapping -- camLeft is a LEFT-pointing unit vector, so +l is
        // world-left/-l is world-right -- v0/v3 (-l, world-right) need U=1 (texture-right) and
        // v1/v2 (+l, world-left) need U=0 (texture-left), or the image renders mirrored.
        vc.addVertex(pose, v0.x, v0.y, v0.z).setColor(255, 255, 255, alpha).setUv(1.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v1.x, v1.y, v1.z).setColor(255, 255, 255, alpha).setUv(0.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v2.x, v2.y, v2.z).setColor(255, 255, 255, alpha).setUv(0.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v3.x, v3.y, v3.z).setColor(255, 255, 255, alpha).setUv(1.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }

    private static RenderType getCircleLayer() {
        if (circleLayer != null) return circleLayer;
        circleLayer = buildLayer("kill_effect_meme_circle", CIRCLE_TEXTURE_ID);
        return circleLayer;
    }

    private static RenderType getArrowLayer() {
        if (arrowLayer != null) return arrowLayer;
        arrowLayer = buildLayer("kill_effect_meme_arrow", ARROW_TEXTURE_ID);
        return arrowLayer;
    }

    private static RenderType buildLayer(String name, Identifier texture) {
        RenderPipeline pipeline = RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.getEntitySnippet(), RenderPipelinesAccessor.getGlobalsSnippet()})
                .withLocation("euclient/" + name)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withCull(false)
                .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create(name, setup);
    }
}
