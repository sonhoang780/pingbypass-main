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
    public BooleanSetting name = new BooleanSetting("Name", "Renders the name of the player mining the block.", false);

    // 2026-08-15 FIX (reported: "không detect được loại mine như Double trong SpeedMine đến từ
    // player khác"). Was keyed by actor ID -- one entry PER PLAYER, period. SpeedMine's own
    // Double mode mines a primary AND a secondary block at once (see SpeedMineModule's
    // primary/secondary pair) -- an opponent using it fires two PlayerMineEvents for the SAME
    // actor at two DIFFERENT positions. Keyed by actor, the second event just overwrote the
    // first entry outright, so only ever one of the two blocks could ever be highlighted at a
    // time, no matter which mode the opponent was in. Keyed by BlockPos instead: every block
    // being mined gets its own entry regardless of how many an actor is working simultaneously.
    private final Map<BlockPos, Mine> mineMap = new HashMap<>();

    @SubscribeEvent
    public void onPlayerMine(PlayerMineEvent event) {
        if(getNull() || event.getActorID() == mc.player.getId()) return;

        // 2026-08-15 (crash fix): actor IDs get reused once the original entity is removed --
        // whatever now sits at that ID by the time this fires isn't guaranteed to still be a
        // Player (crashed as an ItemEntity in the wild, via BlockDestructionProgress.compareTo
        // re-invoking this listener on a stale/repurposed ID). Skip instead of blind-casting.
        if (!(mc.level.getEntity(event.getActorID()) instanceof Player actor)) return;

        // 2026-08-15 REVERT (reported: "làm hỏng breakhighlight"). Was unconditional put() on
        // every PlayerMineEvent -- PlayerMineEvent fires repeatedly while a mine is ONGOING, not
        // just once when it starts, so this was resetting mine.time (and the Easing animation
        // driven by it) practically every tick a player kept mining the same block, never letting
        // the highlight actually progress/complete. Only (re)create the entry when it's genuinely
        // new: no tracked mine at this position yet, or a DIFFERENT actor just started mining a
        // block someone else was already mining (a real restart, not just the same ongoing one).
        Mine existing = mineMap.get(event.getPosition());
        if (existing == null || existing.actorId != event.getActorID()) {
            Mine mine = new Mine(event.getActorID(), WorldUtils.getBreakTime(actor, mc.level.getBlockState(event.getPosition())), System.currentTimeMillis());
            mineMap.put(event.getPosition(), mine);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if(getNull() || mineMap.isEmpty()) return;

        mineMap.entrySet().removeIf(e -> clearMine(e.getValue().actorId, e.getKey()));

        mineMap.forEach((pos, mine) -> {
            if(mc.level.getBlockState(pos).getBlock().equals(Blocks.AIR)) return;

            float scale = Easing.toDelta(mine.time, (int) mine.breakTime);
            AABB box = new AABB(pos).deflate(0.5).inflate(scale / 2.0);
            if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
            if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());

            if (name.getValue()) {
                var actor = mc.level.getEntity(mine.actorId);
                if (actor instanceof Player player) {
                    Renderer3D.renderScaledText(event.getMatrices(), player.getName().getString(), box.getCenter().x, box.maxY + 0.3, box.getCenter().z, 30, true, java.awt.Color.WHITE);
                }
            }
        });
    }

    private boolean clearMine(int id, BlockPos pos) {
        if(mc.level.getEntity(id) == null) return true;
        return Math.sqrt(mc.level.getEntity(id).distanceToSqr(pos.getCenter())) > 6;
    }

    @AllArgsConstructor
    private static class Mine {
        private final int actorId;
        private final float breakTime;
        private final long time;
    }
}
