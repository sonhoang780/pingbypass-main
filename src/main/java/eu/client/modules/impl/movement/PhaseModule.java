package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// Clean port of example-addon-master's PearlPhase (itself dev.leonetic's PhaseModule ported onto
// boze-api) onto this project's own Module/InventoryUtils/NetworkUtils/RotationManager plumbing.
// PearlPhase's own header explains the two things worth carrying over verbatim:
//  - The throw packet always carries an explicit computed yaw/pitch as constructor args, never
//    the live player rotation -- boze's Interaction(action, yaw, pitch) route was tried and
//    measured broken there (mc.gameMode.useItem reads the player's LIVE getYRot()/getXRot(), so
//    whatever the forced rotation did either didn't happen or didn't stick before that read).
//  - solvePitch/reach replace a fixed pitch bucket with a physics-solved pitch that actually
//    lands the pearl ON the computed target: a fixed pitch only ever carries the pearl a small
//    fixed distance sideways while falling to the target's Y, so anything further out was never
//    reachable. reach() simulates the real motion (launch power 1.5 =
//    EnderpearlItem.PROJECTILE_SHOOT_POWER, gravity 0.03 = ThrowableProjectile.getDefaultGravity,
//    drag 0.99/tick, all read off 26.1.2 bytecode) and solvePitch bisects on it since reach
//    shrinks monotonically as pitch steepens.
// Dropped from the source: the AntiCheat ModeOption (boze dispatches per-anticheat interaction
// handlers this project has no equivalent of -- packets here always go out the same way) and the
// NCP-only hazard-place branch (no NCP-specific handling exists in this project either). The
// Teleport mode that used to live in this file's old fixed-pitch version is gone too -- this is
// now a straight port of PearlPhase's pearl-throw mechanism only. FireCharge (this file's own
// pre-port feature, not in PearlPhase) is kept: places fire/flint&steel under the target block
// first to bypass pearl distance checks, reusing the same computed yaw as the throw.
@RegisterModule(name = "Phase", description = "Throws an ender pearl to phase/clip into the block under your feet.", category = Module.Category.MOVEMENT)
public class PhaseModule extends Module {
    public ModeSetting swap = new ModeSetting("Swap", "The mode that will be used for switching to the pearl before throwing it.", "Silent", InventoryUtils.SWITCH_MODES);
    public BooleanSetting crawl = new BooleanSetting("Crawl", "Aims at the exact block underfoot instead of playerY-0.5 -- needed to phase while in the crawling pose, where the lower eye height throws a standing-pose target off.", false);
    public BooleanSetting debug = new BooleanSetting("Debug", "Chat-prints pos/yaw/pitch/target for every throw, so a failed (popped-up) attempt can be matched back to its exact numbers.", false);

    public BooleanSetting fireCharge = new BooleanSetting("FireCharge", "Uses fire to bypass pearl distance checks.", false);
    public BooleanSetting alternative = new BooleanSetting("Alternative", "Uses a flint and steel rather than a fire charge to perform the phase bypass.", false);
    public ModeSetting fireSwitch = new ModeSetting("FireSwitch", "The mode that will be used for switching to a fire charge.", new BooleanSetting.Visibility(fireCharge, true), "Silent", new String[]{"Normal", "Silent", "AltPickup", "AltSwap"});
    public BooleanSetting autoRemove = new BooleanSetting("AutoRemove", "Automatically removes the fire once you have phased.", new BooleanSetting.Visibility(fireCharge, true), true);

    // Legacy PhaseModule-ported math, untouched -- always decides which exact corner (nearest
    // one, purely by position) to target when the throw isn't a near-cardinal straight-throw.
    // The exact integer coordinate IS the mechanism: the collision-resolve ambiguity at a shared
    // block edge is what leaves the entity overlapping solid space, so pulling off it removes the
    // ambiguity and always lands clean on top instead of clipping. Do not pull it inward by an
    // epsilon.
    private static final double CORNER_THRESHOLD = 0.5;
    private static final double CORNER_OFFSET = 0.5;

    private int attempt = 0;

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            setToggled(false);
            return;
        }

        // Scaffolding is climbable, so the player stands INSIDE it as often as on top of it --
        // checking only the player's own position missed the standing-on-top-of-it case
        // (feet.below()) entirely. Scaffolding breaks in a single hit for any tool (hardness ~0),
        // so break it out of the way first instead of aborting Phase entirely.
        for (BlockPos scaffoldPosition : new BlockPos[]{mc.player.blockPosition(), mc.player.blockPosition().below()}) {
            if (!mc.level.getBlockState(scaffoldPosition).is(Blocks.SCAFFOLDING)) continue;

            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, scaffoldPosition, Direction.UP, sequence));
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, scaffoldPosition, Direction.UP, sequence));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            // Instant local feedback, same reasoning as SpeedMine's own client-side removal --
            // don't wait on the server's block-update round-trip before continuing below.
            mc.level.removeBlock(scaffoldPosition, false);
        }

        // Same pose-vs-target-math problem the `crawl` setting's own doc comment already covers
        // for the crouching pose, just for swimming: calculateTargetPos()/boundaryTarget() pick
        // between playerY-0.5 (standing eye offset) and the block-underfoot Y depending only on
        // `crawl`, never on the player's ACTUAL pose. Swim pose has yet another eye height/hitbox
        // shape than either of those two -- throwing with the wrong one of the two available
        // target formulas lands the pearl off-target. `crawl` off means "use the standing target
        // math", which is wrong while genuinely in swim pose, so refuse rather than throw blind.
        boolean swimPose = mc.player.getPose() == Pose.SWIMMING;
        if (!crawl.getValue() && swimPose) {
            setToggled(false);
            return;
        }

        // The one exception to the block below: stuck fully inside a solid block (blockPosition()
        // not replaceable) WHILE in swim pose with `crawl` on is exactly the scenario Phase has to
        // work for -- swim pose's flattened hitbox is what let the player wedge into that 1-block
        // gap in the first place, and it's also the case `crawl`'s block-underfoot target math was
        // written for. Refusing here specifically would block the one case this setting exists to
        // unblock, so skip the stuck-block refusal for that exact combo only.
        boolean crawlSwimStuck = crawl.getValue() && swimPose;
        if (!crawlSwimStuck && !mc.level.getBlockState(mc.player.blockPosition()).canBeReplaced()) {
            setToggled(false);
            return;
        }

        if (mc.player.isCrouching()) {
            setToggled(false);
            return;
        }

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            setToggled(false);
            return;
        }

        int slot = InventoryUtils.find(Items.ENDER_PEARL, 0, swap.getValue().equalsIgnoreCase("AltSwap") || swap.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean didFireCharge = false;

        if (slot == -1) {
            EUClient.CHAT_MANAGER.tagged("No pearls could be found in your hotbar.", getName());
            setToggled(false);
            return;
        }

        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();

        BlockPos downPosition = mc.player.blockPosition().below();

        // Within 22.5 deg of a cardinal direction, aim at the block boundary straight along the
        // camera yaw instead of the position-based corner -- the corner target ignores camera
        // yaw entirely, which breaks backing into a wall while looking elsewhere.
        float rawYaw = Mth.wrapDegrees(mc.player.getYRot());
        float mod90 = ((rawYaw % 90f) + 90f) % 90f;
        boolean nearCardinal = Math.min(mod90, 90f - mod90) < 22.5f;

        Vec3 target = nearCardinal ? boundaryTarget(rawYaw) : calculateTargetPos();

        float yaw = calcYaw(target);
        float pitch = solvePitch(target);

        if (debug.getValue()) {
            attempt++;
            EUClient.CHAT_MANAGER.tagged(String.format(
                    "#%d pos=(%.4f, %.4f, %.4f) yaw=%.2f pitch=%.2f target=(%.4f, %.4f, %.4f) sector=%s",
                    attempt, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    yaw, pitch, target.x, target.y, target.z,
                    nearCardinal ? "straight" : "corner"), getName());
        }

        if (fireCharge.getValue() && mc.level.getBlockState(mc.player.blockPosition()).isAir() && !(mc.level.getBlockState(downPosition).canBeReplaced())) {
            int chargeSlot = InventoryUtils.find(alternative.getValue() ? Items.FLINT_AND_STEEL : Items.FIRE_CHARGE, InventoryUtils.HOTBAR_START, fireSwitch.getValue().equalsIgnoreCase("AltSwap") || fireSwitch.getValue().equalsIgnoreCase("AltPickup") ? 35 : 8);

            if (chargeSlot != -1) {
                EUClient.ROTATION_MANAGER.packetRotate(yaw, 90);

                InventoryUtils.switchSlot(fireSwitch.getValue(), chargeSlot, previousSlot);
                NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(downPosition).add(0, 1, 0), Direction.UP, downPosition, false), sequence));
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                InventoryUtils.switchBack(fireSwitch.getValue(), chargeSlot, previousSlot);

                didFireCharge = true;
            }
        }

        EUClient.ROTATION_MANAGER.packetRotate(yaw, pitch);

        InventoryUtils.switchSlot(swap.getValue(), slot, previousSlot);

        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        InventoryUtils.switchBack(swap.getValue(), slot, previousSlot);

        if (didFireCharge && autoRemove.getValue()) {
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, mc.player.blockPosition(), Direction.UP, sequence));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(prevYaw, prevPitch, mc.player.onGround(), mc.player.horizontalCollision));

        setToggled(false);
    }

    private Vec3 calculateTargetPos() {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double y = crawl.getValue() ? mc.player.blockPosition().below().getY() : mc.player.getY() - 0.5;

        double nearestIntX = Math.round(playerX);
        double nearestIntZ = Math.round(playerZ);
        double dxCorner = nearestIntX - playerX;
        double dzCorner = nearestIntZ - playerZ;

        if (Math.abs(dxCorner) <= CORNER_THRESHOLD && Math.abs(dzCorner) <= CORNER_THRESHOLD) {
            return new Vec3(
                    playerX + Mth.clamp(dxCorner, -CORNER_OFFSET, CORNER_OFFSET),
                    y,
                    playerZ + Mth.clamp(dzCorner, -CORNER_OFFSET, CORNER_OFFSET)
            );
        }

        final double A = Math.PI / 13;
        final double B = Math.PI / 4;

        double x = playerX + Mth.clamp(toClosest(playerX, Math.floor(playerX) + A, Math.floor(playerX) + B) - playerX, -0.2, 0.2);
        double z = playerZ + Mth.clamp(toClosest(playerZ, Math.floor(playerZ) + A, Math.floor(playerZ) + B) - playerZ, -0.2, 0.2);

        return new Vec3(x, y, z);
    }

    private double toClosest(double num, double min, double max) {
        return (num - min) > (max - num) ? max : min;
    }

    // Straight-throw target: near-cardinal yaw only picks WHICH AXIS is "facing" (N/S -> Z,
    // E/W -> X), not which side of it. Which side comes from position (round(), same as the
    // exact-seam target above) instead of the camera direction's sign -- backing into a wall
    // while looking away from it (a real pearl-clip stance) has the wall BEHIND the camera yaw,
    // so picking a side from yaw's sign throws the pearl the wrong way. The other axis stays at
    // the player's own exact position -- a true straight throw along it.
    private Vec3 boundaryTarget(float yawDeg) {
        double px = mc.player.getX(), pz = mc.player.getZ();
        double y = crawl.getValue() ? mc.player.blockPosition().below().getY() : mc.player.getY() - 0.5;

        double rad = Math.toRadians(yawDeg);
        boolean zAxis = Math.abs(Math.cos(rad)) > Math.abs(Math.sin(rad));
        return zAxis ? new Vec3(px, y, Math.round(pz)) : new Vec3(Math.round(px), y, pz);
    }

    private float calcYaw(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }

    /**
     * Pitch that actually lands the pearl on the target -- replaces a fixed-pitch bucket, which
     * only carries the pearl a small fixed distance sideways while it falls to the target's Y,
     * so any target further out than that is never reachable. Falls back to a fixed value for a
     * degenerate (zero-distance) target.
     */
    private float solvePitch(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double d = Math.hypot(target.x - eye.x, target.z - eye.z);
        double drop = eye.y - target.y;
        if (d < 1e-4 || drop <= 1e-4) return mc.player.getBlockY() > 4 ? 85f : 75f;

        // Reach shrinks monotonically as the pitch steepens, so plain bisection converges.
        double lo = 1.0, hi = 89.9;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) * 0.5;
            if (reach(mid, drop) > d) lo = mid; else hi = mid;
        }
        return (float) ((lo + hi) * 0.5);
    }

    /**
     * Horizontal distance a vanilla-thrown pearl covers before falling `drop` blocks, by
     * simulating the real motion: launch power 1.5 (EnderpearlItem.PROJECTILE_SHOOT_POWER),
     * gravity 0.03 (ThrowableProjectile.getDefaultGravity), drag 0.99 per tick.
     */
    private static double reach(double pitchDeg, double drop) {
        double p = Math.toRadians(pitchDeg);
        double vh = 1.5 * Math.cos(p), vy = -1.5 * Math.sin(p);
        double x = 0, y = 0;
        for (int i = 0; i < 200; i++) {
            double prevX = x, prevY = y;
            x += vh;
            y += vy;
            if (y <= -drop) {
                double f = (-drop - prevY) / (y - prevY); // sub-tick crossing of the target plane
                return prevX + (x - prevX) * f;
            }
            vh *= 0.99;
            vy = vy * 0.99 - 0.03;
        }
        return x;
    }
}
