package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.system.Timer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

@RegisterModule(name = "AutoEscape", description = "Eats a chorus fruit to escape as soon as you get trapped on all sides.", category = Module.Category.MISCELLANEOUS)
public class AutoEscapeModule extends Module {
    public BooleanSetting swapBack = new BooleanSetting("SwapBack", "Switches back to the item you were previously holding after eating the chorus fruit.", true);
    public NumberSetting cooldown = new NumberSetting("Cooldown", "The amount of time that has to pass before escaping again.", 1000, 0, 5000);
    public BooleanSetting autoDisable = new BooleanSetting("AutoDisable", "Disables the module after it escapes once.", false);

    // Chorus fruit is NOT instant -- it's a Consumable with a 1.6s eat animation (same as food); the
    // teleport only fires via its onConsume effect once that duration finishes server-side. Switching
    // slots (as the old one-shot version did, immediately after the single use packet) interrupts an
    // in-progress consume, so the eat never actually completed and the "escaped" message was a lie --
    // player stayed trapped, cooldown expired, and the whole broken cycle just repeated forever. Now a
    // real tick-driven state machine: send the use packet once, touch NOTHING else about the held
    // item for the full 1.6s, then swap back only after the consume has actually had time to finish.
    private static final long EAT_DURATION_MS = 1600L;

    private final Timer timer = new Timer();
    private boolean eating = false;
    private long eatingSince = 0L;
    private int previousSlot = -1;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (getNull()) return;

        if (eating) {
            if (System.currentTimeMillis() - eatingSince < EAT_DURATION_MS) return;

            if (swapBack.getValue()) InventoryUtils.switchBackNormal(previousSlot);

            eating = false;
            timer.reset();
            EUClient.CHAT_MANAGER.tagged("Escaped by eating a chorus fruit.", getName());

            if (autoDisable.getValue()) setToggled(false, true);
            return;
        }

        if (!timer.hasTimeElapsed(cooldown.getValue().longValue())) return;
        if (!isTrapped()) return;

        int slot = InventoryUtils.findHotbar(Items.CHORUS_FRUIT);
        if (slot == -1) return;

        previousSlot = mc.player.getInventory().getSelectedSlot();

        InventoryUtils.switchSlot("Normal", slot, previousSlot);
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()));

        eating = true;
        eatingSince = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {
        eating = false;
    }

    // "Trapped" = the block immediately above your head AND all 4 immediate horizontal neighbors
    // at head height are solid -- not blocks further away, only the ones actually touching you.
    private boolean isTrapped() {
        BlockPos head = mc.player.blockPosition().above();

        return isSolid(head.above()) && isSolid(head.north()) && isSolid(head.south()) && isSolid(head.east()) && isSolid(head.west());
    }

    private boolean isSolid(BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }
}
