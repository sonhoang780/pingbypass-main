package eu.client.modules.impl.miscellaneous;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.SettingChangeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.awt.*;
import java.util.ArrayList;

@RegisterModule(name = "AnvilDupe", description = "Automatically places anvils in whitelisted positions.", category = Module.Category.MISCELLANEOUS)
public class AnvilDupeModule extends Module {
    public NumberSetting delay = new NumberSetting("Delay", "The delay for placing anvils.", 10, 0, 100);

    private final ArrayList<BlockPos> whitelist = new ArrayList<>();
    private final ArrayList<BlockPos> whitelistCopy = new ArrayList<>();
    private final Timer timer = new Timer();
    private boolean placeAnvils = false;
    private boolean holding = false;

    @Override
    public void onEnable() {
        whitelist.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (getNull()) return;

        if(mc.player.getMainHandItem().getItem() == Items.ANVIL && mc.options.keyShift.isDown() && !placeAnvils) {
            placeAnvils = true;
            whitelistCopy.clear();
            whitelistCopy.addAll(whitelist);
        }

        // Making your whitelist
        if (mc.player.getMainHandItem().is(ItemTags.SWORDS)) {
            if (!(mc.hitResult instanceof BlockHitResult hitResult)) return;
            BlockPos pos = hitResult.getBlockPos().relative(hitResult.getDirection());

            if (mc.options.keyUse.isDown() && !holding) {
                if (whitelist.contains(pos)) whitelist.remove(pos);
                else whitelist.add(pos);
                holding = true;
            } else {
                holding = false;
            }
        } else if (mc.player.getMainHandItem().getItem() == Items.ANVIL && !whitelistCopy.isEmpty() && placeAnvils) {
            synchronized (whitelistCopy) {
                for (BlockPos pos : new ArrayList<>(whitelistCopy)) {
                    if(!timer.hasTimeElapsed(delay.getValue().intValue() * 10)) break;
                    WorldUtils.placeBlock(pos, WorldUtils.getDirection(pos, false), InteractionHand.MAIN_HAND, true, false);
                    whitelistCopy.remove(pos);
                    timer.reset();
                }
            }

            if(whitelistCopy.isEmpty()) placeAnvils = false;
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if(getNull() || whitelist.isEmpty()) return;

        for(BlockPos pos : whitelist) {
            Renderer3D.renderBox(event.getMatrices(), new AABB(pos), new Color(255, 0, 0, 40));
            Renderer3D.renderBoxOutline(event.getMatrices(), new AABB(pos), new Color(255, 0, 0, 120));
        }
    }
}
