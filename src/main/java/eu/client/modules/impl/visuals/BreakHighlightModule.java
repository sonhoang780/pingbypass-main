package eu.client.modules.impl.visuals;

import lombok.AllArgsConstructor;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMineEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.*;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.WorldUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

@RegisterModule(name = "BreakHighlight", description = "Renders blocks that are being mined by other players.", category = Module.Category.VISUALS)
public class BreakHighlightModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the mine esp.", "Outline", new String[]{"Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private final Map<Integer, Mine> mineMap = new HashMap<>();

    @SubscribeEvent
    public void onPlayerMine(PlayerMineEvent event) {
        if(getNull() || event.getActorID() == mc.player.getId()) return;

        Mine mine = new Mine(event.getPosition(), WorldUtils.getBreakTime((Player) mc.level.getEntity(event.getActorID()), mc.level.getBlockState(event.getPosition())), System.currentTimeMillis());
        if(!mineMap.containsKey(event.getActorID())) {
            mineMap.put(event.getActorID(), mine);
        } else {
            if(!mineMap.get(event.getActorID()).pos.equals(event.getPosition())) mineMap.replace(event.getActorID(), mine);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if(getNull() || mineMap.isEmpty()) return;

        mineMap.entrySet().removeIf(e -> clearMine(e.getKey(), e.getValue().pos));

        mineMap.forEach((id, mine) -> {
            if(mc.level.getBlockState(mine.pos).getBlock().equals(Blocks.AIR)) return;

            float scale = Easing.toDelta(mine.time, (int) mine.breakTime);
            AABB box = new AABB(mine.pos).deflate(0.5).inflate(scale / 2.0);
            if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
            if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
        });
    }

    private boolean clearMine(int id, BlockPos pos) {
        if(mc.level.getEntity(id) == null) return true;
        return Math.sqrt(mc.level.getEntity(id).distanceToSqr(pos.getCenter())) > 6;
    }

    @AllArgsConstructor
    private static class Mine {
        private final BlockPos pos;
        private final float breakTime;
        private final long time;
    }
}
