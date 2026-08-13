package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.HoleUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.ThreadExecutor;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    public BooleanSetting crystalDestruction = new BooleanSetting("CrystalDestruction", "Destroys any crystals that interfere with block placement.", true);
    public BooleanSetting antiStep = new BooleanSetting("AntiStep", "Adds additional blocks that prevent anyone from stepping out of the hole.", false);
    public BooleanSetting antiBomb = new BooleanSetting("AntiBomb", "Places an extra block above your head to prevent you from getting bombed.", false);
    public BooleanSetting holeCheck = new BooleanSetting("HoleCheck", "Only self traps whenever you are in a hole.", false);
    public BooleanSetting whileEating = new BooleanSetting("WhileEating", "Places blocks normally while eating.", true);

    public BooleanSetting selfDisable = new BooleanSetting("SelfDisable", "Toggles off the module once it is finished with placing.", false);

    public BooleanSetting render = new BooleanSetting("Render", "Whether or not to render the place position.", true);

    private List<BlockPos> targetPositions = new ArrayList<>();

    private int ticks = 0;
    private int blocksPlaced = 0;
    private long lastCrystalAttackTime = 0;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (!whileEating.getValue() && mc.player.isUsingItem()) return;

        Runnable runnable = () -> {
            blocksPlaced = 0;
            if (ticks < delay.getValue().intValue()) {
                ticks++;
                return;
            }

            if (autoSwitch.getValue().equalsIgnoreCase("None") && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
                targetPositions = new ArrayList<>();
                return;
            }

            if(holeCheck.getValue() && !HoleUtils.isPlayerInHole(mc.player)) return;

            int slot = InventoryUtils.findHardestBlock(0, autoSwitch.getValue().equalsIgnoreCase("AltSwap") || autoSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
            int previousSlot = mc.player.getInventory().getSelectedSlot();

            if (slot == -1) {
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

            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);

            // Surround's own placement loop (SurroundModule.onPlayerUpdate) uses a single support
            // level and it's enough there because getFeetPositions gives it ankle/floor-level
            // targets already sitting right next to the ground the player is standing on. SelfTrap's
            // Full-mode wall ring sits ONE BLOCK HIGHER (chest height, y+1) than
            // that, so reaching the same ground Surround anchors against directly takes one more
            // fallback level -- a single level (y-1, feet height) can still be open air on a
            // ledge/overhang, with nothing solid until y-2. Two bounded levels, not unbounded
            // cascading/BFS (tried and reverted -- wandered past actual place range chasing a
            // "technically valid" anchor too far from the player to ever be interacted with).
            List<BlockPos> placedPositions = new ArrayList<>();
            for (BlockPos position : targetPositions) {
                if (blocksPlaced >= limit.getValue().intValue()) break;

                Direction direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
                if (direction == null) {
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

                    // Place from the found anchor (bottom of `supports`) back UP toward `position`
                    // -- each newly placed support becomes the next one's own anchor.
                    boolean failed = false;
                    for (int i = supports.size() - 1; i >= 0; i--) {
                        if (blocksPlaced >= limit.getValue().intValue()) { failed = true; break; }

                        BlockPos support = supports.get(i);
                        Direction supportDirection = WorldUtils.getDirection(support, placedPositions, strictDirection.getValue());
                        // Only count/reserve the slot as used when a block ACTUALLY placed -- a
                        // false return means a crystal was in the way and only got attacked this
                        // tick (see WorldUtils.placeBlock), and burning the per-tick limit on a
                        // no-op starved the other, unblocked positions of their own attempt.
                        if (supportDirection == null || !WorldUtils.placeBlock(support, supportDirection, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue())) {
                            failed = true;
                            break;
                        }
                        placedPositions.add(support);
                        blocksPlaced++;

                        // Await: only place one new support per tick, same pacing the original
                        // single-level fallback used.
                        if (await.getValue()) { failed = true; break; }
                    }
                    if (failed) continue;
                    if (blocksPlaced >= limit.getValue().intValue()) break;

                    direction = WorldUtils.getDirection(position, placedPositions, strictDirection.getValue());
                    if (direction == null) continue;
                }

                if (!WorldUtils.placeBlock(position, direction, InteractionHand.MAIN_HAND, rotate.getValue(), crystalDestruction.getValue(), render.getValue()))
                    continue;
                placedPositions.add(position);
                blocksPlaced++;
            }

            InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

            ticks = 0;
        };

        if (asynchronous.getValue()) ThreadExecutor.execute(runnable);
        else runnable.run();
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
        EUClient.ROTATION_MANAGER.packetRotate(RotationUtils.getRotations(best.getX(), best.getEyeY(), best.getZ()));
        mc.player.connection.send(new ServerboundAttackPacket(best.getId()));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
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
        if (mc.player == null || mc.level == null) setToggled(false);
    }

    @Override
    public void onDisable() {
        targetPositions = new ArrayList<>();
    }

    @Override
    public String getMetaData() {
        if (targetPositions == null) return "0";
        return String.valueOf(targetPositions.size());
    }
}
