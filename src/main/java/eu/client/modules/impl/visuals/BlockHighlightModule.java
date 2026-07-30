package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

@RegisterModule(name = "BlockHighlight", description = "Replaces the default Minecraft block highlight with a more customizable one.", category = Module.Category.VISUALS)
public class BlockHighlightModule extends Module {
    public ModeSetting animationMode = new ModeSetting("Animation", "The animation that will be applied to the rendering.", "Static", new String[]{"Static", "Slide"});
    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the target block.", "Outline", new String[]{"None", "Fill", "Outline", "Both"});
    public NumberSetting slideSmoothness = new NumberSetting("Smoothness", "The smoothness for the slide while target block is changing.", 1, 0, 20);
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private BlockPos prevPosition = null;
    private Vec3 renderPosition = null;

    private long animationStart = 0;

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (mode.getValue().equalsIgnoreCase("None")) return;

        if (!(mc.hitResult instanceof BlockHitResult hitResult)) return;

        BlockPos position = hitResult.getBlockPos();

        if(animationMode.getValue().equals("Slide") && position != null) {
            if(renderPosition == null) renderPosition = MathUtils.getVec(position);

            if(!WorldUtils.equals(position, prevPosition)) {
                animationStart = System.currentTimeMillis();
                prevPosition = position;
            }
        }

        Vec3 offset = MathUtils.getVec(position);

        if(animationMode.getValue().equalsIgnoreCase("Slide") && renderPosition != null) {
            float easing = Easing.ease(Easing.toDelta(animationStart, (int) (Math.pow(slideSmoothness.getValue().doubleValue(), 1.4d) * 1000)), Easing.Method.EASE_OUT_QUART);
            renderPosition = renderPosition.add(MathUtils.scale(MathUtils.getVec(position).subtract(renderPosition), easing));

            offset = renderPosition;
        }

        BlockState state = mc.level.getBlockState(position);
        if (state.isAir() || !mc.level.getWorldBorder().isWithinBounds(position)) return;

        VoxelShape shape = state.getShape(mc.level, position);
        if (shape.isEmpty()) return;

        if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), shape.bounds().move(offset), fillColor.getColor());
        if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), shape.bounds().move(offset), outlineColor.getColor());
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }
}
