package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.*;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.HoleUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RegisterModule(name = "HoleESP", description = "Highlights all holes in a specified radius.", category = Module.Category.VISUALS)
public class HoleESPModule extends Module {
    public NumberSetting range = new NumberSetting("Range", "The maximum range at which holes will be rendered.", 10, 1, 50);
    public BooleanSetting asynchronous = new BooleanSetting("Asynchronous", "Performs calculations on separate threads.", true);
    public BooleanSetting doubleHoles = new BooleanSetting("DoubleHoles", "Whether or not to render the ESP on double holes.", true);
    public BooleanSetting quadHoles = new BooleanSetting("QuadHoles", "Whether or not to render the ESP on quad holes.", true);
    public BooleanSetting fade = new BooleanSetting("Fade", "Fades the holes in and out based on your distance to them.", false);

    public ModeSetting fill = new ModeSetting("Fill", "The mode for the fill rendering on the hole boxes.", "Normal", new String[]{"None", "Normal", "Gradient"});
    public NumberSetting fillHeight = new NumberSetting("FillHeight", "The height of the fill rendering on the holes.", new ModeSetting.Visibility(fill, "Normal", "Gradient"), 1.0, -2.0, 2.0);
    public ModeSetting outline = new ModeSetting("Outline", "The mode for the outline rendering on the hole boxes.", "Normal", new String[]{"None", "Normal", "Gradient"});
    public NumberSetting outlineHeight = new NumberSetting("OutlineHeight", "The height of the outline rendering on the holes.", new ModeSetting.Visibility(outline, "Normal", "Gradient"), 1.0, -2.0, 2.0);

    public CategorySetting safeColorsCategory = new CategorySetting("Safe", "The category that contains the settings for coloring of safe (all-bedrock) holes.");
    public ColorSetting safeFillColor = new ColorSetting("SafeFillColor", "Fill", "The color for the fill rendering on safe holes.", new CategorySetting.Visibility(safeColorsCategory), new ColorSetting.Color(new Color(0, 255, 0, ColorUtils.getDefaultFillColor().getColor().getAlpha()), false, false));
    public ColorSetting safeOutlineColor = new ColorSetting("SafeOutlineColor", "Outline", "The color for the outline rendering on safe holes.", new CategorySetting.Visibility(safeColorsCategory), new ColorSetting.Color(new Color(0, 255, 0, ColorUtils.getDefaultOutlineColor().getColor().getAlpha()), false, false));

    public CategorySetting unsafeColorsCategory = new CategorySetting("Unsafe", "The category that contains the settings for coloring of unsafe (blast-proof but not pure bedrock) holes.");
    public ColorSetting unsafeFillColor = new ColorSetting("UnsafeFillColor", "Fill", "The color for the fill rendering on unsafe holes.", new CategorySetting.Visibility(unsafeColorsCategory), new ColorSetting.Color(new Color(255, 0, 0, ColorUtils.getDefaultFillColor().getColor().getAlpha()), false, false));
    public ColorSetting unsafeOutlineColor = new ColorSetting("UnsafeOutlineColor", "Outline", "The color for the outline rendering on unsafe holes.", new CategorySetting.Visibility(unsafeColorsCategory), new ColorSetting.Color(new Color(255, 0, 0, ColorUtils.getDefaultOutlineColor().getColor().getAlpha()), false, false));

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<HoleUtils.Hole> holes = Collections.synchronizedList(new ArrayList<>());

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        List<BlockPos> sphere = new ArrayList<>();
        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(range.getValue().doubleValue()); i++) {
            sphere.add(mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i)));
        }

        Runnable runnable = () -> {
            List<HoleUtils.Hole> holes = new ArrayList<>();
            for (BlockPos position : sphere) {
                HoleUtils.Hole singleHole = HoleUtils.getSingleHole(position, 0);
                if (singleHole != null) {
                    holes.add(singleHole);
                    continue;
                }

                if (doubleHoles.getValue()) {
                    HoleUtils.Hole doubleHole = HoleUtils.getDoubleHole(position, 0);
                    if (doubleHole != null) {
                        holes.add(doubleHole);
                        continue;
                    }
                }

                if (quadHoles.getValue()) {
                    HoleUtils.Hole quadHole = HoleUtils.getQuadHole(position, 0);
                    if (quadHole != null) {
                        holes.add(quadHole);
                    }
                }
            }

            synchronized (this.holes) {
                this.holes.clear();
                this.holes.addAll(holes);
            }
        };

        if (asynchronous.getValue()) executor.submit(runnable);
        else runnable.run();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.level == null) return;

        synchronized (holes) {
            if (holes.isEmpty()) return;

            for (HoleUtils.Hole hole : holes) {
                AABB filledBox = new AABB(hole.box().minX, hole.box().minY, hole.box().minZ, hole.box().maxX, hole.box().minY + fillHeight.getValue().doubleValue(), hole.box().maxZ);
                AABB outlinedBox = new AABB(hole.box().minX, hole.box().minY, hole.box().minZ, hole.box().maxX, hole.box().minY + outlineHeight.getValue().doubleValue(), hole.box().maxZ);

                if (fill.getValue().equalsIgnoreCase("Normal")) Renderer3D.renderBox(event.getMatrices(), filledBox, getFillColor(hole));
                if (fill.getValue().equalsIgnoreCase("Gradient")) Renderer3D.renderGradientBox(event.getMatrices(), filledBox, fillHeight.getValue().floatValue() < 0.0f ? getFillColor(hole) : new Color(0, 0, 0, 0), fillHeight.getValue().floatValue() < 0.0f ? new Color(0, 0, 0, 0) : getFillColor(hole));

                if (outline.getValue().equalsIgnoreCase("Normal")) Renderer3D.renderBoxOutline(event.getMatrices(), outlinedBox, getOutlineColor(hole));
                if (outline.getValue().equalsIgnoreCase("Gradient")) Renderer3D.renderGradientBoxOutline(event.getMatrices(), outlinedBox, outlineHeight.getValue().floatValue() < 0.0f ? getOutlineColor(hole) : new Color(0, 0, 0, 0), outlineHeight.getValue().floatValue() < 0.0f ? new Color(0, 0, 0, 0) : getOutlineColor(hole));
            }
        }
    }

    @Override
    public String getMetaData() {
        return String.valueOf(holes.size());
    }

    private Color getFillColor(HoleUtils.Hole hole) {
        Color color = switch (hole.safety()) {
            case SAFE -> safeFillColor.getColor();
            default -> unsafeFillColor.getColor();
        };
        if(!fade.getValue()) return color;

        return ColorUtils.getColor(color, (int) (color.getAlpha() * getEasing(hole)));
    }

    private Color getOutlineColor(HoleUtils.Hole hole) {
        Color color = switch (hole.safety()) {
            case SAFE -> safeOutlineColor.getColor();
            default -> unsafeOutlineColor.getColor();
        };
        if(!fade.getValue()) return color;

        return ColorUtils.getColor(color, (int) (color.getAlpha() * getEasing(hole)));
    }

    private float getEasing(HoleUtils.Hole hole) {
        float scale = (float) (1.0f - Mth.clamp(Math.sqrt(mc.player.distanceToSqr(hole.box().getCenter())) / range.getValue().doubleValue(), 0.0f, 1.0f));
        return Easing.ease(scale, Easing.Method.EASE_OUT_CUBIC);
    }
}
