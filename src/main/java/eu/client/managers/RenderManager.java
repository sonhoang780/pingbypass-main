package eu.client.managers;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.impl.combat.AutoCrystalModule;
import eu.client.modules.impl.core.RendersModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.EspShader;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.miscellaneous.RenderPosition;
import eu.client.utils.system.Counter;
import eu.client.utils.system.MathUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RenderManager implements IMinecraft {
    private final Counter counter = new Counter();
    @Getter private int fps;
    @Getter public CopyOnWriteArrayList<RenderPosition> renderPositions = new CopyOnWriteArrayList<>();

    private Target crystalTarget;
    private BlockPos prevPosition = null;
    private Vec3 renderPosition = null;

    private long animationStart = 0;

    public RenderManager() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderOverlayEvent event) {
        fps = counter.getCount();
        counter.increment();
    }

    @SubscribeEvent
    public void onRenderWorld$placePositions(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null || renderPositions.isEmpty()) return;
        RendersModule module = EUClient.MODULE_MANAGER.getModule(RendersModule.class);

        for (RenderPosition position : renderPositions) {
            float scale = position.get();
            AABB box = new AABB(position.getPos());
            if (module.mode.getValue().equals("Shrink")) box = new AABB(position.getPos()).deflate(0.5).inflate(Mth.clamp(scale / 2.0, 0.0, 0.5));

            if (module.renderMode.getValue().equalsIgnoreCase("Fill") || module.renderMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, module.getColor(module.mode.getValue(), module.fillColor.getColor(), scale));
            if (module.renderMode.getValue().equalsIgnoreCase("Outline") || module.renderMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, module.getColor(module.mode.getValue(), module.outlineColor.getColor(), scale));
        }

        renderPositions.removeIf(p -> p.get() <= 0);
    }

    @SubscribeEvent
    public void onRenderWorld$autoCrystal(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (crystalTarget == null || crystalTarget.getPosition() == null) return;

        AutoCrystalModule autoCrystalModule = EUClient.MODULE_MANAGER.getModule(AutoCrystalModule.class);

        float scale;
        if (crystalTarget.getTarget() == 1) scale = Easing.ease(Easing.toDelta(crystalTarget.getTime(), autoCrystalModule.duration.getValue().intValue()), Easing.Method.EASE_OUT_CUBIC);
        else scale = 1.0f - Easing.ease(Easing.toDelta(crystalTarget.getTime(), autoCrystalModule.duration.getValue().intValue()), Easing.Method.EASE_IN_CUBIC);

        AABB box = new AABB(crystalTarget.getPosition());
        if (autoCrystalModule.mode.getValue().equals("Shrink")) box = new AABB(crystalTarget.getPosition()).deflate(0.5).inflate(Mth.clamp(scale / 2.0, 0.0, 0.5));

        if(autoCrystalModule.animationMode.getValue().equals("Slide")) {
            if(renderPosition == null) renderPosition = MathUtils.getVec(crystalTarget.getPosition());

            if(!WorldUtils.equals(crystalTarget.getPosition(), prevPosition)) {
                animationStart = System.currentTimeMillis();
                prevPosition = crystalTarget.getPosition();
            }

            float easing = Easing.ease(Easing.toDelta(animationStart, (int) (Math.pow(autoCrystalModule.slideSmoothness.getValue().doubleValue(), 1.4) * 1000)), Easing.Method.EASE_OUT_QUART);
            renderPosition = renderPosition.add(MathUtils.scale(MathUtils.getVec(crystalTarget.getPosition()).subtract(renderPosition), easing));

            box = MathUtils.getBox(renderPosition);
            if (autoCrystalModule.mode.getValue().equals("Shrink")) box = MathUtils.getBox(renderPosition).deflate(0.5).inflate(Mth.clamp(scale / 2.0, 0.0, 0.5));
        }

        RendersModule renders = EUClient.MODULE_MANAGER.getModule(RendersModule.class);
        boolean fill = autoCrystalModule.renderMode.getValue().equalsIgnoreCase("Fill") || autoCrystalModule.renderMode.getValue().equalsIgnoreCase("Both");
        boolean outline = autoCrystalModule.renderMode.getValue().equalsIgnoreCase("Outline") || autoCrystalModule.renderMode.getValue().equalsIgnoreCase("Both");

        // Shader != None routes the exact same geometry into Renderer3D's SHADER_* lists instead, so
        // the animated fragment shader replaces the flat fill/outline colors. The configured colors
        // still matter: only their ALPHA survives (the shader multiplies vertexColor.a), which is how
        // Sydney faded the shaded box in and out with the place animation.
        int effect = EspShader.modeIndex(autoCrystalModule.shader.getValue());
        List<Renderer3D.VertexCollection> quadSink = effect == 0 ? Renderer3D.QUADS : Renderer3D.SHADER_QUADS;
        List<Renderer3D.VertexCollection> lineSink = effect == 0 ? Renderer3D.DEBUG_LINES : Renderer3D.SHADER_DEBUG_LINES;

        if (effect != 0) {
            float speed = autoCrystalModule.shaderSpeed.getValue().floatValue();
            float step = autoCrystalModule.shaderStep.getValue().floatValue();
            // Sydney folded speed (and, for Gradient only, step) straight into `time` rather than
            // uploading them as animation-rate uniforms -- keep that or Gradient scrolls at a
            // completely different rate than it did there.
            float time = (System.currentTimeMillis() % 2000000L) / 1000.0f * speed * (effect == 1 ? step : 1.0f);
            float distance = autoCrystalModule.shaderDistanceScaling.getValue()
                    ? (float) Math.sqrt(mc.player.distanceToSqr(box.getCenter()))
                    : 1.0f;

            EspShader.setSettings(new EspShader.Settings(effect, time, step,
                    autoCrystalModule.shaderOpacity.getValue().floatValue() / 100.0f, distance,
                    effect == 7 ? autoCrystalModule.shaderGlowColor.getColor() : autoCrystalModule.shaderColor1.getColor(),
                    autoCrystalModule.shaderColor2.getColor(),
                    autoCrystalModule.shaderColor3.getColor(),
                    autoCrystalModule.shaderColor4.getColor()));
        }

        if (fill)
            Renderer3D.renderGradientBox(quadSink, event.getMatrices(), box, renders.getColor(autoCrystalModule.mode.getValue(), autoCrystalModule.fillColorUp.getColor(), scale), renders.getColor(autoCrystalModule.mode.getValue(), autoCrystalModule.fillColorDown.getColor(), scale));
        if (outline)
            Renderer3D.renderGradientBoxOutline(lineSink, event.getMatrices(), box, renders.getColor(autoCrystalModule.mode.getValue(), autoCrystalModule.outlineColorUp.getColor(), scale), renders.getColor(autoCrystalModule.mode.getValue(), autoCrystalModule.outlineColorDown.getColor(), scale));
    }

    @SubscribeEvent
    public void onRenderWorld$autoCrystalExtra(RenderWorldEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (crystalTarget == null || crystalTarget.getPosition() == null) return;
        if (crystalTarget.getTarget() != 1) return;

        PoseStack matrices = event.getMatrices();
        AutoCrystalModule module = EUClient.MODULE_MANAGER.getModule(AutoCrystalModule.class);

        Vec3 vec3d = new Vec3(crystalTarget.getPosition().getCenter().x - mc.getEntityRenderDispatcher().camera.position().x, crystalTarget.getPosition().getCenter().y - mc.getEntityRenderDispatcher().camera.position().y, crystalTarget.getPosition().getCenter().z - mc.getEntityRenderDispatcher().camera.position().z);
        if(module.animationMode.getValue().equals("Slide")) vec3d = new Vec3(renderPosition.x + 0.5 - mc.getEntityRenderDispatcher().camera.position().x, renderPosition.y + 0.5 - mc.getEntityRenderDispatcher().camera.position().y, renderPosition.z + 0.5 - mc.getEntityRenderDispatcher().camera.position().z);

        if (module.icon.getValue()) {
            float scaling = module.iconScale.getValue().floatValue() / 100.0f;

            matrices.pushPose();
            matrices.translate(vec3d.x, vec3d.y, vec3d.z);
            matrices.mulPose(mc.getEntityRenderDispatcher().camera.rotation());
            matrices.scale(scaling, -scaling, scaling);

            Renderer2D.renderCircle(matrices, 0, 0, 12.f, new Color(0, 0, 0, 100));
            Renderer2D.renderCircle(matrices, 0, 0, 12.0f - module.iconRadius.getValue().floatValue(), module.iconColor.getColor());

            if (module.renderDamage.getValue()) {
                Renderer2D.renderTexture(matrices, -5.5f, -8.5f, 5.5f, 2.5f, Identifier.fromNamespaceAndPath(EUClient.MOD_ID, "textures/crystal.png"), Color.WHITE);

                matrices.pushPose();
                matrices.scale(0.45f, 0.45f, 0.45f);

                String text = module.getCalculationDamage();
                EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -EUClient.FONT_MANAGER.getWidth(text) / 2 - 1, 7, mc.renderBuffers().bufferSource(), Color.WHITE);

                matrices.popPose();
            } else {
                Renderer2D.renderTexture(matrices, -6.5f, -6.5f, 6.5f, 6.5f, Identifier.fromNamespaceAndPath(EUClient.MOD_ID, "textures/crystal.png"), Color.WHITE);
            }

            matrices.popPose();
        } else {
            if (module.renderDamage.getValue()) {
                matrices.pushPose();
                matrices.translate(vec3d.x, vec3d.y, vec3d.z);
                matrices.mulPose(mc.getEntityRenderDispatcher().camera.rotation());
                matrices.scale(0.025f, -0.025f, 0.025f);

                String text = module.getCalculationDamage();
                EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -EUClient.FONT_MANAGER.getWidth(text) / 2, -EUClient.FONT_MANAGER.getHeight() / 2, mc.renderBuffers().bufferSource(), Color.WHITE);

                matrices.popPose();
            }
        }
    }

    public void setRenderPosition(BlockPos position) {
        if (!EUClient.MODULE_MANAGER.getModule(AutoCrystalModule.class).isToggled()) position = null;

        if (position == null) {
            if (crystalTarget != null) {
                if (crystalTarget.getTarget() != 0) {
                    crystalTarget.setTarget(0);
                    crystalTarget.setTime(System.currentTimeMillis());
                }
            } else {
                crystalTarget = new Target(null, 0, System.currentTimeMillis());
            }
        } else {
            if (crystalTarget == null || crystalTarget.getTarget() == 0) {
                crystalTarget = new Target(position, 1, System.currentTimeMillis());
            } else {
                crystalTarget.setPosition(position);
            }
        }

        // Sync render position to connected client when running as proxy
        if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                && EUClient.PROXY_SERVER != null) {
            sendRenderPositionToClient(position);
        }
    }

    private void sendRenderPositionToClient(BlockPos position) {
        var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                        new eu.client.pingbypass.protocol.packets.S2CRenderPositionPacket(position)));
        for (net.minecraft.network.Connection conn : EUClient.PROXY_SERVER.getConnections()) {
            if (conn.isConnected()) {
                conn.send(packet);
            }
        }
    }

    @AllArgsConstructor @Getter @Setter
    public static class Target {
        private BlockPos position;
        private int target;
        private long time;
    }
}
