package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;

@RegisterModule(name = "NoSlow", description = "Removes the slowness effect that you receive when doing certain actions.", category = Module.Category.MOVEMENT)
public class NoSlowModule extends Module {
    public BooleanSetting items = new BooleanSetting("Items", "Removes the slowness effect from eating or using items.", true);
    public ModeSetting itemMode = new ModeSetting("Mode", "Item slowdown bypass mode.", "Normal", new String[]{"Normal", "Strict", "GrimV2", "GrimV3"});

    public BooleanSetting crawl = new BooleanSetting("Crawl", "Removes the slowness effect from crawling.", false);
    public BooleanSetting sneak = new BooleanSetting("Sneak", "Removes the slowness effect from sneaking.", false);
    public BooleanSetting soulSand = new BooleanSetting("SoulSand", "Removes the slowness effect from walking on soul sand.", false);
    public BooleanSetting slimeBlocks = new BooleanSetting("SlimeBlocks", "Removes the slowness effect from walking on slime blocks.", false);
    public BooleanSetting honeyBlocks = new BooleanSetting("HoneyBlocks", "Removes the slowness effect from walking on honey blocks.", false);

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!items.getValue() || !mc.player.isUsingItem() || mc.player.isPassenger() || mc.player.isFallFlying()) return;

        float yaw = EUClient.ROTATION_MANAGER.getRotation() != null ? EUClient.ROTATION_MANAGER.getRotation().getYaw() : mc.player.getYRot();
        float pitch = EUClient.ROTATION_MANAGER.getRotation() != null ? EUClient.ROTATION_MANAGER.getRotation().getPitch() : mc.player.getXRot();

        // 2026-08-19 FIFTH BUG / actual root cause of "still rubberbands on ViaFabricPlus 1.21.1 and
        // 1.20.x, but never on native 1.21.2+". The slot-swap branch that used to live here cannot
        // work on a pre-1.21.2 server by that server's own rules, and it was never the difference:
        //   * Every mechanism that clears the server's item-use state before 1.21.2 (SetCarriedItem
        //     on MAIN_HAND, RELEASE_USE_ITEM, SWAP_ITEM_WITH_OFFHAND, container click) is the same
        //     call -- stopUsingItem() -- which also zeroes useItemRemainingTicks, so the eat can
        //     never finish. That is the eat-cancel regression this branch already produced, and no
        //     amount of re-sending UseItem fixes it (each resend restarts the countdown from full).
        //   * Firing only on the sprint rising edge left the far more common case -- eating while
        //     merely WALKING -- with the client slowdown cancelled (see shouldSlow()) and the server
        //     still applying it every tick. That mismatch, not the sprint transition, is what the
        //     user actually sees as a rubberband.
        // What genuinely differs about 1.21.2+ is version semantics, not the wire path: item-use
        // slowdown/sprint permission became data-driven there (LocalPlayer#isSlowDueToUsingItem ->
        // UseEffects.canSprint()/speedMultiplier(), verified in .mcref), and the GrimV2 distraction
        // packet only reads as "use ended" against that model. On a ≤1.21.1 server (native or
        // Via-translated -- ViaFabricPlus only rewrites the packet FORMAT, the server-side handler
        // is that version's own) a use-item for the empty other hand does nothing at all: the
        // main-hand use continues, the slowdown stays, and cancelling ours guarantees a setback.
        // So on legacy protocol GrimV2 falls back to GrimV3's timing gate (see shouldSlow()) instead
        // of pretending, and this branch is gone.
        if (itemMode.getValue().equals("GrimV2")) {
            // 2026-08-19 ROOT CAUSE (reported: "GrimV2 vẫn bị chậm"). The distraction packet was
            // gated on `!canUseItem(otherHand)` -- i.e. it was only ever sent when the OTHER hand
            // held nothing the module classifies as usable. Hold a shield (or a bow/crossbow/second
            // gapple/trident/spyglass/goat horn) in the off hand while eating from the main hand and
            // BOTH branches fall through and nothing at all goes on the wire: the mode silently
            // became a no-op, the client still cancelled its own slowdown (shouldSlow() returns
            // false here), and the server kept applying the real one -- which is exactly what the
            // reported "still slowed"/rubberband is.
            //
            // Checked against the verified working implementation instead of patching the gate:
            // Nami's NoSlowFeature (nami-public-mc.1.21.11, Mode.GRIM, onPreTick) sends the fake use
            // to the opposite hand UNCONDITIONALLY -- it never inspects what is in that hand. Its
            // only two guards are that the item actually being used is food, and that the player is
            // not sneaking. Matched exactly. The food guard also fixes the other half of the old
            // shape: it used to fire for ANY isUsingItem (drawing a bow, blocking, spyglass), which
            // spammed a real use of the other hand every tick for no benefit.
            //
            // ponytail: known ceiling -- if the other hand holds something the server WILL start
            // using (a shield), the server swaps its active hand to that instead of the food, so it
            // stays "using an item" and the slowdown persists. That is a real limitation of this
            // mechanism (Nami's included), not a bug in this port; use GrimV3 for those cases.
            if (!mc.player.isShiftKeyDown() && mc.player.getUseItem().has(DataComponents.FOOD)) {
                InteractionHand distraction = mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                NetworkUtils.sendSequencedPacket(id -> new ServerboundUseItemPacket(distraction, id, yaw, pitch));
            }
        }
    }

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!items.getValue() || !mc.player.isUsingItem() || mc.player.isPassenger() || mc.player.isFallFlying()) return;

        if (itemMode.getValue().equals("Strict") && !NetworkUtils.isLegacyProtocol()) {
            if (mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                int slot = mc.player.getInventory().getSelectedSlot();
                mc.getConnection().send(new ServerboundSetCarriedItemPacket((slot + 1) % 9));
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            } else {
                float yaw = EUClient.ROTATION_MANAGER.getRotation() != null ? EUClient.ROTATION_MANAGER.getRotation().getYaw() : mc.player.getYRot();
                float pitch = EUClient.ROTATION_MANAGER.getRotation() != null ? EUClient.ROTATION_MANAGER.getRotation().getPitch() : mc.player.getXRot();
                NetworkUtils.sendSequencedPacket(id -> new ServerboundUseItemPacket(InteractionHand.OFF_HAND, id, yaw, pitch));
            }
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (itemMode.getValue().equals("Strict")) {
            if (event.getPacket() instanceof ServerboundMovePlayerPacket.PosRot || event.getPacket() instanceof ServerboundMovePlayerPacket.Pos || event.getPacket() instanceof ServerboundMovePlayerPacket.Rot || event.getPacket() instanceof ServerboundMovePlayerPacket.StatusOnly) {
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
            }

            if (event.getPacket() instanceof ServerboundContainerClickPacket) {
                if (mc.player.isUsingItem()) mc.player.stopUsingItem();
                if (mc.player.isSprinting()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                if (mc.player.isShiftKeyDown()) mc.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
            }
        }
    }

    @Override
    public void onDisable() {
        EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);
    }

    public boolean canUseItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.has(DataComponents.CONSUMABLE)
                || stack.has(DataComponents.FOOD)
                || stack.is(Items.BOW)
                || stack.is(Items.CROSSBOW)
                || stack.is(Items.SHIELD)
                || stack.is(Items.TRIDENT)
                || stack.is(Items.SPYGLASS)
                || stack.is(Items.GOAT_HORN);
    }

    public boolean canBypassGrimUseTime() {
        if (mc.player == null) return false;
        return mc.player.getTicksUsingItem() < 5 || (mc.player.getUseItemRemainingTicks() > 1 && mc.player.getUseItemRemainingTicks() % 2 != 0);
    }

    public boolean shouldSlow() {
        if (mc.player == null || !items.getValue()) return true;
        // GrimV2's distraction packet is a no-op on a pre-1.21.2 server (see onPlayerUpdate), so
        // cancelling our own slowdown there is a guaranteed client/server mismatch. Fall back to
        // GrimV3's timing gate, which exploits the use-tick accounting rather than the version's
        // item-use semantics and is therefore protocol-agnostic.
        if (itemMode.getValue().equals("GrimV3") || (itemMode.getValue().equals("GrimV2") && NetworkUtils.isLegacyProtocol())) {
            return !canBypassGrimUseTime();
        }
        return false;
    }
}
