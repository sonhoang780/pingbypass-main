package eu.client.utils.minecraft;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// Client-side memory of the last ender chest the player opened. The ender chest item itself never
// stores its contents, so -- like Meteor's EChestMemory -- we snapshot the contents from the
// container packets while the player has an ender chest GUI open, and remember them so .peek can
// show them later without opening the chest again.
//
// Not a Module/Command, so it isn't auto-registered by Reflections. PeekCommand calls
// EChestMemory.init() once (lazy) to subscribe it to the event bus.
public final class EChestMemory {
    private static final EChestMemory INSTANCE = new EChestMemory();
    private static boolean registered = false;

    // The remembered 27 slots. null until the player has opened an ender chest at least once.
    private static NonNullList<ItemStack> items = null;

    // The containerId of the GUI we currently believe is an ender chest, or -1 if none.
    private int trackedContainerId = -1;

    private EChestMemory() {}

    /** Called by PeekCommand. Subscribes this listener to the event bus exactly once. */
    public static void init() {
        if (registered) return;
        registered = true;
        EUClient.EVENT_HANDLER.subscribe(INSTANCE);
    }

    /** True once we've captured an ender chest at least once this session. */
    public static boolean hasItems() {
        return items != null;
    }

    /** The remembered contents (27 slots), or null if never captured. */
    public static NonNullList<ItemStack> getItems() {
        return items;
    }

    @SubscribeEvent
    public void onPacket(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundOpenScreenPacket open) {
            // We only care that a container just opened. Decide if it's an ender chest by checking
            // the block the player is currently looking at (matches how the player opened it).
            if (isLookingAtEnderChest()) {
                trackedContainerId = open.getContainerId();
                // Fresh capture buffer; content packet will fill it.
                items = NonNullList.withSize(27, ItemStack.EMPTY);
            } else {
                trackedContainerId = -1;
            }
            return;
        }

        if (trackedContainerId == -1) return;

        if (event.getPacket() instanceof ClientboundContainerSetContentPacket content) {
            if (content.containerId() != trackedContainerId) return;
            NonNullList<ItemStack> buffer = NonNullList.withSize(27, ItemStack.EMPTY);
            // items() includes the player inventory slots after the 27 chest slots; copy only 0..26.
            for (int i = 0; i < 27 && i < content.items().size(); i++) {
                buffer.set(i, content.items().get(i).copy());
            }
            items = buffer;
            return;
        }

        if (event.getPacket() instanceof ClientboundContainerSetSlotPacket slot) {
            if (slot.getContainerId() != trackedContainerId) return;
            int index = slot.getSlot();
            if (items != null && index >= 0 && index < 27) {
                items.set(index, slot.getItem().copy());
            }
        }
    }

    private boolean isLookingAtEnderChest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) return false;
        BlockPos pos = blockHit.getBlockPos();
        return mc.level.getBlockState(pos).is(Blocks.ENDER_CHEST);
    }
}