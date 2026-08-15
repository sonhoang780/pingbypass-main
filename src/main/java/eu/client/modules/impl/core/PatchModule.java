package eu.client.modules.impl.core;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;

// Ported 2026-08-15 from NamiDevelopment/nami-public's PatchFeature ("Client" category --
// "any kind of hotfixes you should apply based on what server and ac u on"). Always-on/config-only
// like RotationsModule (persistent, no Bind row) -- Nami's own PatchFeature force-re-enables
// itself in its constructor the same way, it's a settings bag, not something you toggle off.
//
// Two of Nami's four toggles didn't come along:
//   - SlotDragDesync: an empty/unimplemented setting in their own source (no logic reads it at
//     all) -- nothing real to port.
//   - TPSCooldownSync: needs a live server-TPS tracker this project doesn't have yet. Skipped
//     rather than faked with a setting that silently does nothing on two of its three values.
@RegisterModule(name = "Patch", description = "Server/anticheat-specific hotfixes.", category = Module.Category.CORE, persistent = true, drawn = false)
public class PatchModule extends Module {
    // Player.causeExtraKnockback() (called on the ATTACKER when landing extra/critical knockback)
    // pushes the target, then also slows/desprints the ATTACKER's own client-predicted movement
    // (a GrimAC quirk) -- see PlayerEntityMixin's grimAttackVelocity$setDeltaMovement/$setSprinting
    // redirects for where this is actually applied; this setting is only the switch.
    public BooleanSetting grimAttackVelocity = new BooleanSetting("GrimAttackVelocity", "Stops your own sprint and movement from being cut after landing extra knockback on a hit.", false);

    // The server sometimes echoes back a ClientboundContainerSetSlotPacket for the hotbar slot
    // you're already holding, confirming an item+count you already have -- redundant, but applying
    // it anyway makes that slot's icon visibly flicker for one frame. Cancel it when the packet's
    // item+count already matches what's in hand; nothing is lost since the client's own state was
    // already correct.
    public BooleanSetting silentSwapFix = new BooleanSetting("SilentSwapFix", "Stops the hotbar slot you're holding from flickering when the server re-sends a slot update that already matches what you're holding.", false);

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null) return;
        if (!silentSwapFix.getValue()) return;

        if (!(event.getPacket() instanceof ClientboundContainerSetSlotPacket packet)) return;
        if (packet.getContainerId() != 0) return; // player inventory only

        int slot = packet.getSlot();
        if (slot < 36 || slot > 44) return; // hotbar only

        ItemStack packetStack = packet.getItem();
        ItemStack handStack = mc.player.getMainHandItem();

        if (packetStack.isEmpty() || handStack.isEmpty()) return;
        if (packetStack.getItem() == handStack.getItem() && packetStack.getCount() == handStack.getCount()) {
            event.setCancelled(true);
        }
    }
}
