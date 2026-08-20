package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TippedArrowItem;

import java.util.concurrent.ConcurrentLinkedQueue;

//BEWARE: CHINESE CODE AHEAD
@RegisterModule(name = "SelfBow", description = "Automatically shoots arrows at you in order to give yourself potion effects.", category = Module.Category.COMBAT)
public class SelfBowModule extends Module {
    public BooleanSetting manual = new BooleanSetting("Manual", "Whether or not to do the self bow manually.", false);
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for switching slots.", "Normal", new String[]{"None", "Normal"});
    public NumberSetting chargeTime = new NumberSetting("ChargeTime", "The amount of ticks that the module will be charging the bow for.", 4, 0, 20);
    public BooleanSetting effectCycle = new BooleanSetting("EffectCycle", "Fires multiple arrows in case of having more than one arrow type.", false);

    private boolean switched = false;
    private boolean todo = false;
    private boolean first = false;

    private int previousSlot = -1;
    private int chargeTicks = 0;
    private int bestArrow = -1;
    private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;

        int slot = InventoryUtils.findHotbar(Items.BOW);

        if(manual.getValue()) {
            boolean flag = mc.player.isUsingItem() && mc.player.getInventory().getSelectedItem().getItem() == Items.BOW;
            if(flag && !todo) {
                if(effectCycle.getValue()) findArrows();
                todo = true;
                first = true;
            }

            if(todo) {
                if(mc.player.getInventory().getSelectedItem().getItem() != Items.BOW) {
                    todo = false;
                    mc.options.keyUse.setDown(false);
                    return;
                }

                mc.options.keyUse.setDown(true);
            }

            if(!todo) return;
        } else {
            if (autoSwitch.getValue().equals("None") && mc.player.getInventory().getSelectedItem().getItem() != Items.BOW) {
                EUClient.CHAT_MANAGER.tagged("You are currently not holding a bow.", getName());
                setToggled(false);
                return;
            }

            if (!autoSwitch.getValue().equals("None") && slot == -1) {
                EUClient.CHAT_MANAGER.tagged("Could not find a bow in your hotbar.", getName());
                setToggled(false);
                return;
            }

            if (mc.player.getMainHandItem().getItem() != Items.BOW) {
                InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
                switched = true;
            }

            mc.options.keyUse.setDown(true);
        }

        if(chargeTicks < chargeTime.getValue().intValue() - (first ? 1 : 0)) {
            chargeTicks++;
            return;
        }

        if (effectCycle.getValue() && !queue.isEmpty()) {
            int arrow = queue.poll();
            if (arrow != bestArrow) InventoryUtils.swap("Pickup", arrow, bestArrow);
        }

        if (mc.getConnection() != null) {
            eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() ->
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                            EUClient.POSITION_MANAGER.getServerX(), EUClient.POSITION_MANAGER.getServerY(),
                            EUClient.POSITION_MANAGER.getServerZ(), mc.player.getYRot(), -90.0f,
                            EUClient.POSITION_MANAGER.isServerOnGround(), mc.player.horizontalCollision)));
        }
        mc.options.keyUse.setDown(false);
        mc.gameMode.releaseUsingItem(mc.player);
        chargeTicks = 0;
        first = false;

        if (!effectCycle.getValue() || queue.isEmpty()) {
            if(manual.getValue()) {
                todo = false;
                mc.options.keyUse.setDown(false);
            } else {
                setToggled(false);
            }
        }
    }

    @Override
    public void onEnable() {
        if(!getNull()) {
            if(effectCycle.getValue() && !manual.getValue()) findArrows();
            previousSlot = mc.player.getInventory().getSelectedSlot();
        }
    }

    @Override
    public void onDisable() {
        if (mc.player == null || mc.level == null) return;

        if (switched) InventoryUtils.switchSlot(autoSwitch.getValue(), previousSlot, previousSlot);
        mc.options.keyUse.setDown(false);
    }

    private void findArrows() {
        bestArrow = -1;
        for(int i = 9; i < 36; i++) {
            if(mc.player.getInventory().getItem(i).isEmpty()) continue;

            Item item = mc.player.getInventory().getItem(i).getItem();
            if(item instanceof TippedArrowItem) {
                if(bestArrow == -1) bestArrow = i;
                queue.add(i);
            }
        }
    }

    @Override
    public String getMetaData() {
        int chargeTicks = mc.player.getInventory().getSelectedItem().getItem() == Items.BOW ? mc.player.getTicksUsingItem() : 0;
        return String.valueOf(chargeTicks);
    }
}
