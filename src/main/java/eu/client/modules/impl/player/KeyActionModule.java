package eu.client.modules.impl.player;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.KeyInputEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BindSetting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.modules.impl.player.MultiTaskModule;
import eu.client.utils.input.KeyboardUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

// Merged ThrowFirework/ThrowPearl/ThrowXP into one always-present module: three independent
// keybinds instead of three separate modules each occupying the module's own bind slot.
//
// persistent = true: this module has no meaningful "enabled" state of its own (the three binds
// work independently of it, same as AutoArmor's ElytraBind is independent of AutoArmor's own
// toggle) -- persistent modules can never be toggled at all (Module.setToggled() is a no-op),
// which is also what makes ModuleManager stop adding the normally-universal top-level "Bind"
// row for this module (see its own doc): that row would otherwise offer a keybind for
// "toggle this module", a state that literally cannot change, and reported as "Bind tổng ở
// KeyAction ... không đại diện cho cái gì cả".
//
// Each action (FireWork/Pearls/XP) is its own CategorySetting: the bind itself is one of the
// settings INSIDE it, only visible once right-clicked open -- exactly HUDModule's own
// watermarkCategory/watermark shape, reused here for the same reason (a compact header row per
// action, its own settings tucked away until asked for).
@RegisterModule(name = "KeyAction", description = "Keybinds for throwing fireworks, pearls and experience bottles.", category = Module.Category.PLAYER, persistent = true, proxyEnhanced = true)
public class KeyActionModule extends Module {
    public CategorySetting fireworkCategory = new CategorySetting("FireWork", "Keybind and settings for throwing a firework.");
    public BindSetting fireworkBind = new BindSetting("FireWorkBind", "Bind", "The key that throws a firework. Hold/ReverseHold repeat it every tick the bind is (or isn't) held.", new CategorySetting.Visibility(fireworkCategory), 0);
    public ModeSetting fireworkSwitch = new ModeSetting("FireWorkSwitch", "Switch", "The mode that will be used for automatically switching to fireworks.", new CategorySetting.Visibility(fireworkCategory), "Silent", InventoryUtils.SWITCH_MODES);

    public CategorySetting pearlsCategory = new CategorySetting("Pearls", "Keybind and settings for throwing an ender pearl.");
    public BindSetting pearlsBind = new BindSetting("PearlsBind", "Bind", "The key that throws an ender pearl. Hold/ReverseHold repeat it every tick the bind is (or isn't) held.", new CategorySetting.Visibility(pearlsCategory), 0);
    public ModeSetting pearlsSwitch = new ModeSetting("PearlsSwitch", "Switch", "The mode that will be used for automatically switching to pearls.", new CategorySetting.Visibility(pearlsCategory), "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting pearlsRotate = new BooleanSetting("PearlsRotate", "Rotate", "Sends a packet rotation right before throwing the pearl.", new CategorySetting.Visibility(pearlsCategory), true);

    public CategorySetting xpCategory = new CategorySetting("XP", "Keybind and settings for throwing experience bottles.");
    public BindSetting xpBind = new BindSetting("XPBind", "Bind", "The key that starts/stops throwing experience bottles. Hold/ReverseHold keep the loop matched to whether the bind is (or isn't) held instead of toggling it.", new CategorySetting.Visibility(xpCategory), 0);
    public ModeSetting xpSwitch = new ModeSetting("XPSwitch", "Switch", "The mode that will be used for automatically switching to experience bottles.", new CategorySetting.Visibility(xpCategory), "Silent", InventoryUtils.SWITCH_MODES);
    public NumberSetting xpDelay = new NumberSetting("XPDelay", "Delay", "The delay in ticks between throwing experience bottles.", new CategorySetting.Visibility(xpCategory), 1, 0, 20);
    public NumberSetting xpRepeat = new NumberSetting("XPRepeat", "Repeat", "Allows you to throw a lot more XP bottles at once.", new CategorySetting.Visibility(xpCategory), 1, 1, 15);
    public ModeSetting xpAntiWaste = new ModeSetting("XPAntiWaste", "AntiWaste", "How wasting of experience bottles should be prevented.", new CategorySetting.Visibility(xpCategory), "Avoid", new String[]{"None", "Avoid", "Disable"});
    public BooleanSetting xpItemDisable = new BooleanSetting("XPItemDisable", "ItemDisable", "Automatically stops the XP loop when you run out of XP.", new CategorySetting.Visibility(xpCategory), true);

    private boolean xpActive = false;
    private int xpTicks = 0;

    /** Whether the XP-bottle-throwing loop is currently running. Read by AutoCrystal/ServerAutoCrystal's
     *  anti-kick check, which used to read ThrowXPModule.isToggled() before this merge. */
    public boolean isXpActive() {
        return xpActive;
    }

    // Bind mode only -- fires once per physical key press. Hold/ReverseHold are driven
    // continuously from onPlayerUpdate below instead (a press/release EVENT can't express
    // "keep firing every tick this is held").
    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        if (shouldRunOnProxy() || getNull()) return;

        if (fireworkBind.getValue() != 0 && event.getKey() == fireworkBind.getValue() && fireworkBind.getMode().equals("Bind")) throwFirework();
        if (pearlsBind.getValue() != 0 && event.getKey() == pearlsBind.getValue() && pearlsBind.getMode().equals("Bind")) throwPearl();
        if (xpBind.getValue() != 0 && event.getKey() == xpBind.getValue() && xpBind.getMode().equals("Bind")) xpActive = !xpActive;
    }

    // ---- FireWork: verbatim ThrowFireworkModule.onEnable(), fired directly instead of via
    // module-toggle -- it was already a one-shot ("setToggled(false)" at the end), so a direct
    // call loses nothing. isOnCooldown() below naturally rate-limits Hold mode's every-tick calls
    // to the item's real throw cadence.
    private void throwFirework() {
        if (mc.player == null || mc.level == null) return;

        if (fireworkSwitch.getValue().equalsIgnoreCase("None") && mc.player.getMainHandItem().getItem() != Items.FIREWORK_ROCKET) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any fireworks.", getName());
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.FIREWORK_ROCKET))) return;

        int slot = InventoryUtils.find(Items.FIREWORK_ROCKET, 0, fireworkSwitch.getValue().equalsIgnoreCase("AltSwap") || fireworkSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No fireworks could be found in your hotbar.", getName());
            return;
        }

        InventoryUtils.switchSlot(fireworkSwitch.getValue(), slot, previousSlot);
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()));
        InventoryUtils.switchBack(fireworkSwitch.getValue(), slot, previousSlot);
    }

    // ---- Pearls: verbatim ThrowPearlModule.onEnable(), same direct-call treatment.
    private long lastPearlTime = 0;

    public boolean isPearlActive() {
        return System.currentTimeMillis() - lastPearlTime < 100;
    }

    private void throwPearl() {
        if (mc.player == null || mc.level == null) return;

        if (pearlsSwitch.getValue().equalsIgnoreCase("None") && mc.player.getMainHandItem().getItem() != Items.ENDER_PEARL) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any pearls.", getName());
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) return;

        lastPearlTime = System.currentTimeMillis();

        // MultiTask (Pearl): while eating, force AltSwap. AltSwap moves the pearl via container SWAP
        // clicks and never changes the selected slot, so the server never fires stopUsingItem --
        // unlike Silent/Normal which change the held slot and cancel the eat.
        MultiTaskModule multiTask = EUClient.MODULE_MANAGER.getModule(MultiTaskModule.class);
        boolean keepEating = multiTask != null && multiTask.isToggled() && multiTask.pearl.getValue() && mc.player.isUsingItem();
        String pearlMode = keepEating ? "AltSwap" : pearlsSwitch.getValue();

        int slot = InventoryUtils.find(Items.ENDER_PEARL, 0,
                (pearlMode.equalsIgnoreCase("AltSwap") || pearlMode.equalsIgnoreCase("AltPickup")) ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No pearls could be found in your hotbar.", getName());
            return;
        }
        if (pearlsRotate.getValue()) EUClient.ROTATION_MANAGER.packetRotate(mc.player.getYRot(), mc.player.getXRot());
        InventoryUtils.switchSlot(pearlMode, slot, previousSlot);

        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()), this::serverSend);
        serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        InventoryUtils.switchBack(pearlMode, slot, previousSlot);
    }

    // Hold/ReverseHold for FireWork/Pearls, same shape as ModuleManager's own module-bind Hold
    // handling: "should this fire THIS tick" == (mode is Hold) == (key is physically down).
    // Bind mode is skipped here entirely -- KeyInputEvent's press-edge already covers it.
    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy() || getNull()) return;

        checkHoldBind(fireworkBind, this::throwFirework);
        checkHoldBind(pearlsBind, this::throwPearl);

        if (xpBind.getValue() != 0 && !xpBind.getMode().equals("Bind")) {
            boolean held = KeyboardUtils.isBindDown(xpBind.getValue());
            xpActive = xpBind.getMode().equals("Hold") == held;
        }

        tickXp();
    }

    private void checkHoldBind(BindSetting bind, Runnable action) {
        if (bind.getValue() == 0 || bind.getMode().equals("Bind")) return;
        boolean held = KeyboardUtils.isBindDown(bind.getValue());
        if (bind.getMode().equals("Hold") == held) action.run();
    }

    // ---- XP: verbatim ThrowXPModule.onPlayerUpdate()/needsExperience(), gated on xpActive
    // (the bind's toggle/hold state above) instead of the whole module's own isToggled().
    private void tickXp() {
        if (!xpActive) return;
        // On the client, defer to the proxy instead of running here too -- see ThrowXPModule's
        // original doc for why (one round trip per packet through the dumb pipe otherwise).
        if (shouldRunOnProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if (xpSwitch.getValue().equalsIgnoreCase("None") && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            EUClient.CHAT_MANAGER.tagged("You are currently not holding any experience bottles.", getName());
            xpActive = false;
            return;
        }

        if (xpTicks < xpDelay.getValue().intValue()) {
            xpTicks++;
            return;
        }

        if (!needsExperience() && !xpAntiWaste.getValue().equals("None")) {
            if (xpAntiWaste.getValue().equals("Disable")) xpActive = false;
            return;
        }

        int slot = InventoryUtils.find(Items.EXPERIENCE_BOTTLE, 0, xpSwitch.getValue().equalsIgnoreCase("AltSwap") || xpSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No experience bottles could be found in your hotbar.", getName());
            xpActive = false;
            return;
        }

        InventoryUtils.switchSlot(xpSwitch.getValue(), slot, previousSlot);

        for (int i = 0; i < xpRepeat.getValue().intValue(); i++)
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot()), this::serverSend);
        serverSend(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        InventoryUtils.switchBack(xpSwitch.getValue(), slot, previousSlot);

        xpTicks = 0;
    }

    /**
     * Sends directly to the real server connection when running on the proxy (skipping the
     * extra client<->proxy hop the dumb pipe would otherwise add); falls back to the normal
     * connection everywhere else.
     */
    private void serverSend(net.minecraft.network.protocol.Packet<?> packet) {
        if (isRunningOnProxy() && EUClient.PROXY_SERVER != null) {
            var serverConn = EUClient.PROXY_SERVER.getServerConnection();
            if (serverConn != null && serverConn.isConnected()) {
                eu.client.pingbypass.server.ProxyServerTickListener.allowSend(() -> serverConn.send(packet));
                return;
            }
        }
        mc.getConnection().send(packet);
    }

    private boolean needsExperience() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!stack.isEmpty() && (stack.is(ItemTags.FOOT_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.HEAD_ARMOR)) && (Math.round(((stack.getMaxDamage() - stack.getDamageValue()) * 100.0f) / stack.getMaxDamage()) < 100.0f)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onDisable() {
        xpActive = false;
        xpTicks = 0;
    }
}
