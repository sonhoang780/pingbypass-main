package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.system.ThreadExecutor;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

@RegisterModule(name = "SelfTrap", description = "Automatically places blocks around you to prevent other people from getting inside your hole.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class SelfTrapModule extends Module {
    public ModeSetting autoSwitch = new ModeSetting("Switch", "The mode that will be used for automatically switching to necessary items.", "Silent", InventoryUtils.SWITCH_MODES);
    public ModeSetting mode = new ModeSetting("Mode", "The offsets that will be used when trapping.", "Partial", new String[]{"Partial", "Full"});
    public BooleanSetting head = new BooleanSetting("Head", "Whether or not to cover the block on the players head.", new ModeSetting.Visibility(mode, "Full"), true);
    public BooleanSetting asynchronous = new BooleanSetting("Asynchronous", "Performs calculations on separate threads.", true);
    public NumberSetting limit = new NumberSetting("Limit", "The number of blocks that can be placed per tick.", 4, 1, 20);
    public NumberSetting delay = new NumberSetting("Delay", "The amount of ticks that have to be waited for between placements.", 0, 0, 20);
    public BooleanSetting await = new BooleanSetting("Await", "Waits for blocks to be registered by the client before placing on them.", false);
    public BooleanSetting rotate = new BooleanSetting("Rotate", "Sends a packet rotation whenever placing a block.", true);
    public BooleanSetting strictDirection = new BooleanSetting("StrictDirection", "Only places using directions that face you.", false);
    public BooleanSetting airPlace = new BooleanSetting("AirPlace", "Places blocks in the air without needing neighboring blocks.", false);
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Destroys any crystals that interfere with block placement.", true);
    public BooleanSetting antiStep = new BooleanSetting("AntiStep", "Adds additional blocks that prevent anyone from stepping out of the hole.", false);
    public BooleanSetting antiBomb = new BooleanSetting("AntiBomb", "Places an extra block above your head to prevent you from getting bombed.", false);
    public BooleanSetting holeCheck = new BooleanSetting("HoleCheck", "Only self traps whenever you are in a hole.", false);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Places blocks normally while eating.", true);

    public BooleanSetting selfDisable = new BooleanSetting("SelfDisable", "Toggles off the module once it is finished with placing.", false);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private int packetsSent = 0;
    private boolean isWorking = false;

    @Override
    public String getMetaData() {
        return "Packet: " + packetsSent;
    }

    private List<BlockPos> targetPositions = new ArrayList<>();

    private int ticks = 0;
    private int blocksPlaced = 0;
    private long lastCrystalAttackTime = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (!whileEating.getValue() && eu.client.utils.minecraft.EntityUtils.isEating()) return;

        Runnable runnable = () -> {
            blocksPlaced = 0;
            if (ticks < delay.getValue().intValue()) {
                ticks++;
                return;
            }

            // [FIXED] Block cầm trên tay phải là block chống nổ
            if (autoSwitch.getValue().equalsIgnoreCase("None") && (!(mc.player.getMainHandItem().getItem() instanceof BlockItem blockItem) || !isBlastProof(blockItem.getBlock()))) {
                EUClient.CHAT_MANAGER.tagged("You are currently not holding any blast-proof blocks.", getName());
                setToggled(false);
                targetPositions = new ArrayList<>();
                return;
            }

            if(holeCheck.getValue() && !HoleUtils.isPlayerInHole(mc.player)) return;

            int slot = findBlastProofBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (slot == -1) {
                EUClient.CHAT_MANAGER.tagged("No blast-proof blocks could be found in your hotbar.", getName());
                setToggled(false);
                targetPositions = new ArrayList<>();
                return;
            }

            targetPositions = HoleUtils.getTrapPositions(mc.player, mode.getValue().equalsIgnoreCase("Partial"), head.getValue(), antiStep.getValue(), antiBomb.getValue(), strictDirection.getValue()).stream().filter(WorldUtils::isPlaceable).toList();

            // Ported from Surround's own homovore-based fix (findThreateningCrystal/breakCrystal) --
            // was only ever reacting to a crystal AFTER a placement attempt happened to touch it
            // (WorldUtils.placeBlock's own crystalDestruction, single-shot, no retry if the attack
            // packet gets dropped). Proactively re-targets whatever crystal is currently blocking
            // any wanted trap position, every tick, until it's actually dead -- same fix, same gap.
            attackThreateningCrystal();

            if (targetPositions.isEmpty()) {
                if (selfDisable.getValue()) setToggled(false);
                return;
            }

            isWorking = true;
            try {
                if (blocksPlaced > limit.getValue().intValue()) return;



                InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

                List<BlockPos> placedPositions = new ArrayList<>();
                for (BlockPos position : targetPositions) {
                    if (blocksPlaced >= limit.getValue().intValue()) break;

                    Direction direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
                    if (direction == null) {
                        if (airPlace.getValue()) {
                            direction = Direction.UP;
                        } else {
                            List<BlockPos> supports = new ArrayList<>();
                            BlockPos probe = position;
                            Direction anchorDirection = null;

                            for (int level = 0; level < 2; level++) {
                                probe = probe.below();
                                if (!WorldUtils.isPlaceable(probe)) break;
                                supports.add(probe);
                                anchorDirection = WorldUtils.getDirection(probe, placedPositions, strictDirection.getValue());
                                if (anchorDirection != null) break;
                            }

                            if (anchorDirection == null) continue;

                            boolean failed = false;
                            for (int i = supports.size() - 1; i >= 0; i--) {
                                if (blocksPlaced >= limit.getValue().intValue()) { failed = true; break; }

                                BlockPos support = supports.get(i);
                                Direction supportDirection = WorldUtils.getDirection(support, placedPositions, strictDirection.getValue());
                                if (supportDirection == null || !WorldUtils.placeBlock(support, supportDirection, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue())) {
                                    failed = true;
                                    break;
                                }
                                placedPositions.add(support);
                                blocksPlaced++;

                                if (await.getValue()) { failed = true; break; }
                            }
                            if (failed) continue;
                            if (blocksPlaced >= limit.getValue().intValue()) break;

                            direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
                            if (direction == null) continue;
                        }
                    }

                    if (!WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue()))
                        continue;
                    placedPositions.add(position);
                    blocksPlaced++;
                }

                InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

                ticks = 0;
            } finally {
                isWorking = false;
            }
        };

        if (asynchronous.getValue()) ThreadExecutor.execute(runnable);
        else runnable.run();
    }

    // branch) -- SelfTrap had only the tick-polled attackThreateningCrystal below, up to a full
    // tick slower to react than Surround's instant off-the-spawn-packet attack. Uses packet.getId()
    // for the actual attack (the real server-assigned id) -- NOT a freshly `new`'d EndCrystal's own
    // id, which comes from Entity's local static counter and doesn't correspond to anything on the
    // server (that was Surround's own bug before this port, silently no-opping every reactive
    // attack it ever sent regardless of ping).
    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!crystalDestruction.getValue() || mc.player == null || mc.level == null) return;
        if (!(event.getPacket() instanceof ClientboundAddEntityPacket packet) || !packet.getType().equals(EntityType.END_CRYSTAL))
            return;

        EndCrystal crystal = new EndCrystal(mc.level, packet.getX(), packet.getY(), packet.getZ());
        for (BlockPos position : targetPositions) {
            if (!new AABB(position).intersects(crystal.getBoundingBox())) continue;

            if (blocksPlaced > limit.getValue().intValue()) return;
            if (!whileEating.getValue() && eu.client.utils.minecraft.EntityUtils.isEating()) return;

            int slot = findBlastProofBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
            int previousSlot = mc.player.getInventory().getSelectedSlot();
            if (slot == -1) return;

            Direction direction = WorldUtils.getDirection(position, strictDirection.getValue());
            if (direction == null) return;

            // Netty IO thread -- see SurroundModule's identical guard / InventoryUtils.switchSlot.
            if (!InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot)) return;
            WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, () -> {
                mc.player.connection.send(new ServerboundAttackPacket(packet.getId()));
            }, rotate.getValue(), false, render.getValue());
            blocksPlaced++;
            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);
            break;
        }
    }

    // Ported from Surround's own homovore-based fix (findThreateningCrystal/breakCrystal) -- rate-
    // limited to 50ms, same as Surround's copy.
    private void attackThreateningCrystal() {
        if (!crystalDestruction.getValue()) return;

        long now = System.currentTimeMillis();
        if (now - lastCrystalAttackTime < 50L) return;

        Vec3 eye = mc.player.getEyePosition();
        EndCrystal best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (net.minecraft.world.entity.Entity entity : mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(mc.player.blockPosition()).inflate(8.0), e -> e instanceof EndCrystal)) {
            EndCrystal crystal = (EndCrystal) entity;
            if (!threatensTrap(crystal.blockPosition())) continue;

            double distSq = crystal.distanceToSqr(eye);
            if (distSq > 36.0 || distSq >= bestDistSq) continue;
            bestDistSq = distSq;
            best = crystal;
        }

        if (best == null) return;

        lastCrystalAttackTime = now;
        // No packetRotate -- see WorldUtils.destroyCrystals's own doc: attack reach is pure
        // distance, and faking a look here only risks tripping rotation-based anticheat right next
        // to an attack packet for zero gain. Matches Shoreline's own crystal-defense attack.
        mc.player.connection.send(new ServerboundAttackPacket(best.getId()));
    }

    // Same 1-horizontal/2-vertical adjacency radius as Surround's threatensSurround.
    private boolean threatensTrap(BlockPos cell) {
        for (BlockPos pos : targetPositions) {
            if (Math.abs(pos.getX() - cell.getX()) <= 1 && Math.abs(pos.getZ() - cell.getZ()) <= 1 && Math.abs(pos.getY() - cell.getY()) <= 2) return true;
        }
        return false;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) return;
        packetsSent = 0;
        targetPositions.clear();
    }

    @Override
    public void onDisable() {
        targetPositions = new ArrayList<>();
    }



    // ----- Helper Methods cho Blast-Proof Blocks -----
    private boolean isBlastProof(net.minecraft.world.level.block.Block block) {
        return block == Blocks.OBSIDIAN ||
               block == Blocks.ENDER_CHEST ||
               block == Blocks.CRYING_OBSIDIAN ||
               block == Blocks.NETHERITE_BLOCK ||
               block == Blocks.RESPAWN_ANCHOR ||
               block == Blocks.ANCIENT_DEBRIS ||
               block == Blocks.ENCHANTING_TABLE ||
               block == Blocks.ANVIL ||
               block == Blocks.CHIPPED_ANVIL ||
               block == Blocks.DAMAGED_ANVIL;
    }

    private int findBlastProofBlock(int start, int end) {
        float bestHardness = -1;
        int bestSlot = -1;

        for (int i = start; i <= end; i++) {
            if (!(mc.player.getInventory().getItem(i).getItem() instanceof BlockItem item)) continue;

            net.minecraft.world.level.block.Block block = item.getBlock();
            if (!isBlastProof(block)) continue;

            float hardness = block.defaultDestroyTime();
            if (hardness == -1) return i; 
            if (hardness > bestHardness) {
                bestHardness = hardness;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    @SubscribeEvent
    public void onPacketSend(eu.client.events.impl.PacketSendEvent.Post event) {
        if (isWorking) {
            packetsSent++;
        }
    }
}
