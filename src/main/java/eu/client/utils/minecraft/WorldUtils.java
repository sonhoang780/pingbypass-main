package eu.client.utils.minecraft;

import com.google.common.collect.Sets;
import eu.client.EUClient;
import eu.client.modules.impl.movement.HitboxDesyncModule;
import eu.client.utils.IMinecraft;
import eu.client.utils.miscellaneous.RenderPosition;
import eu.client.utils.rotations.RotationUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class WorldUtils implements IMinecraft {
    public static Set<Block> RIGHT_CLICKABLE_BLOCKS = Sets.newHashSet(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX, Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX, Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX, Blocks.ANVIL, Blocks.BELL, Blocks.OAK_BUTTON, Blocks.ACACIA_BUTTON, Blocks.BIRCH_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.JUNGLE_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.STONE_BUTTON, Blocks.COMPARATOR, Blocks.REPEATER, Blocks.OAK_FENCE_GATE, Blocks.SPRUCE_FENCE_GATE, Blocks.BIRCH_FENCE_GATE, Blocks.JUNGLE_FENCE_GATE, Blocks.DARK_OAK_FENCE_GATE, Blocks.ACACIA_FENCE_GATE, Blocks.BREWING_STAND, Blocks.DISPENSER, Blocks.DROPPER, Blocks.LEVER, Blocks.NOTE_BLOCK, Blocks.JUKEBOX, Blocks.BEACON, Blocks.BLACK_BED, Blocks.BLUE_BED, Blocks.BROWN_BED, Blocks.CYAN_BED, Blocks.GRAY_BED, Blocks.GREEN_BED, Blocks.LIGHT_BLUE_BED, Blocks.LIGHT_GRAY_BED, Blocks.LIME_BED, Blocks.MAGENTA_BED, Blocks.ORANGE_BED, Blocks.PINK_BED, Blocks.PURPLE_BED, Blocks.RED_BED, Blocks.WHITE_BED, Blocks.YELLOW_BED, Blocks.FURNACE, Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR, Blocks.JUNGLE_DOOR, Blocks.ACACIA_DOOR, Blocks.DARK_OAK_DOOR, Blocks.CAKE, Blocks.ENCHANTING_TABLE, Blocks.DRAGON_EGG, Blocks.HOPPER, Blocks.REPEATING_COMMAND_BLOCK, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.CRAFTING_TABLE, Blocks.ACACIA_TRAPDOOR, Blocks.BIRCH_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR, Blocks.JUNGLE_TRAPDOOR, Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.CAKE, Blocks.ACACIA_SIGN, Blocks.ACACIA_WALL_SIGN, Blocks.BIRCH_SIGN, Blocks.BIRCH_WALL_SIGN, Blocks.DARK_OAK_SIGN, Blocks.DARK_OAK_WALL_SIGN, Blocks.JUNGLE_SIGN, Blocks.JUNGLE_WALL_SIGN, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.SPRUCE_SIGN, Blocks.SPRUCE_WALL_SIGN, Blocks.CRIMSON_SIGN, Blocks.CRIMSON_WALL_SIGN, Blocks.WARPED_SIGN, Blocks.WARPED_WALL_SIGN, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CARTOGRAPHY_TABLE, Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.LOOM, Blocks.STONECUTTER, Blocks.SMITHING_TABLE);
    private static final ItemStack NETHERITE_PICKAXE = new ItemStack(Items.NETHERITE_PICKAXE);

    public static boolean placeBlock(BlockPos position, Direction direction, InteractionHand hand, boolean rotate, boolean crystalDestruction) {
        return placeBlock(position, direction, hand, rotate, crystalDestruction, false);
    }

    public static boolean placeBlock(BlockPos position, Direction direction, InteractionHand hand, boolean rotate, boolean crystalDestruction, boolean render) {
        return placeBlock(position, direction, hand, null, rotate, crystalDestruction, render);
    }

    // Returns false when the placement packet was SKIPPED this call (a crystal sat where we were
    // about to place, so we only sent the attack packet instead) -- true when the actual
    // place packet went out. Attacking then immediately placing in the SAME call sent the place
    // packet before the server had any chance to process the crystal's death, so the server's own
    // isUnobstructed check still saw the (about to die, but not dead YET) crystal's hitbox in the
    // way and rejected the placement outright -- matches the reported "AutoCrystal drops a crystal
    // near my SelfTrap/Surround spot and I can never place there" (SelfTrap especially: it only
    // ever attempts once, so that single same-tick race was a guaranteed permanent failure, not
    // just an occasional flicker). Skipping the place packet here forces the caller to retry on
    // its OWN next tick, by which point the server has actually removed the crystal.
    public static boolean placeBlock(BlockPos position, Direction direction, InteractionHand hand, Runnable runnable, boolean rotate, boolean crystalDestruction, boolean render) {
        Vec3 vec3d = position.getCenter();
        BlockPos offsetPosition;

        if (direction == null) {
            direction = Direction.UP;
            offsetPosition = position;
        } else {
            offsetPosition = position.relative(direction);
            vec3d = vec3d.add(direction.getStepX() / 2.0, direction.getStepY() / 2.0, direction.getStepZ() / 2.0);
        }

        // Publish this cell as wanted BEFORE anything else runs this tick -- AutoCrystal reads
        // WorldManager.isReserved() when picking its own placement spot, so it stops re-filling a
        // cell a placement module is actively trying to use instead of winning the whack-a-mole
        // every tick (PlayerUpdateEvent, which placement modules run on, fires before AutoCrystal's
        // own UpdateMovementEvent.Post placement).
        EUClient.WORLD_MANAGER.reservePlacement(position);

        // Snapshotted before the rotate, restored after the place, iff RotationsModule.SnapBack is
        // on -- verbatim from bản gốc 1.21.4's own placeBlock. The port had dropped both this and
        // the setting itself (see RotationsModule's restore note); packetRotate has no restore of
        // its own, so without this the server keeps believing the last faked look indefinitely.
        float prevYaw = EUClient.ROTATION_MANAGER.getServerYaw();
        float prevPitch = EUClient.ROTATION_MANAGER.getServerPitch();

        // 2026-08-15 FIX (reported: Surround/SelfTrap with Rotate off can't place at all on a
        // native 1.21.x connection, works fine via 1.20.x). Mojang's 1.21.2 networking rewrite
        // added server-side interaction-consistency validation: ServerboundUseItemOnPacket is
        // checked against the player's last-KNOWN rotation (from the most recent movement packet
        // that carried one), and a clicked face too far outside that look gets rejected outright
        // -- a real vanilla server behavior, not a specific anticheat's. Pre-1.21.2 protocols
        // never had this check, so ViaFabricPlus's 1.20.x translation never triggers it (the real
        // server it talks to is native 1.21.x, but nothing in the OLD packet shape asks for the
        // validation vanilla now gates on). rotate=false used to mean "send no rotation update at
        // all", which is exactly what a native connection now rejects: the server's last-known
        // rotation is whatever the camera happened to be facing tick ago, essentially never
        // pointed at `position`. Silent packet-only rotation (see RotationManager.silentRotate's
        // own doc) satisfies that server-side check the same way Normal/Packet rotate does,
        // without moving the camera or touching movement -- exactly what "Rotate off" means to
        // the user (no VISIBLE rotate), not "tell the server nothing".
        if (rotate) EUClient.ROTATION_MANAGER.packetRotate(RotationUtils.getRotations(vec3d.x, vec3d.y, vec3d.z));
        else EUClient.ROTATION_MANAGER.silentRotate(RotationUtils.getRotations(vec3d.x, vec3d.y, vec3d.z));
        if (crystalDestruction) {
            Direction finalDirection = direction;
            // Retry the instant the server confirms the blocking crystal is actually dead
            // (WorldManager listens for ClientboundRemoveEntitiesPacket) instead of only waiting
            // for the caller's own next tick -- shrinks the re-place race window against an enemy
            // contesting the same cell.
            Runnable retry = () -> placeBlock(position, finalDirection, hand, runnable, rotate, crystalDestruction, render);
            if (destroyCrystals(position, mc.player.getEyePosition(), vec3d, retry)) return false;
        }
        if (runnable != null) runnable.run();

        boolean sprint = mc.player.isSprinting();
        boolean sneak = WorldUtils.RIGHT_CLICKABLE_BLOCKS.contains(mc.level.getBlockState(offsetPosition).getBlock()) && !mc.player.isShiftKeyDown();

        // On the proxy, skip sprint stop/start — the client controls sprint state.
        // This saves 2 packets per block placement and significantly speeds up Surround.
        boolean isProxy = eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer();
        if (!isProxy && sprint) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        if (sneak) mc.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));

        BlockHitResult blockHitResult = new BlockHitResult(vec3d, direction.getOpposite(), offsetPosition, false);
        NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemOnPacket(hand, blockHitResult, sequence));
        mc.getConnection().send(new ServerboundSwingPacket(hand));

        if (rotate && EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.core.RotationsModule.class).snapBack.getValue())
            EUClient.ROTATION_MANAGER.packetRotate(prevYaw, prevPitch);

        EUClient.WORLD_MANAGER.getPlaceTimer().reset();

        if (sneak) mc.player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
        if (!isProxy && sprint) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));

        if (render) {
            RenderPosition renderPosition = new RenderPosition(position);
            if(!EUClient.RENDER_MANAGER.renderPositions.contains(renderPosition)) {
                EUClient.RENDER_MANAGER.renderPositions.add(renderPosition);

                // Sync render to connected client when running as proxy
                if (eu.client.pingbypass.PingBypassFlags.proxyForwardingActive
                        && EUClient.PINGBYPASS_CONFIG != null && EUClient.PINGBYPASS_CONFIG.isServer()
                        && EUClient.PROXY_SERVER != null) {
                    var packet = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                            eu.client.pingbypass.protocol.PbCustomPayload.fromPacket(
                                    new eu.client.pingbypass.protocol.packets.S2CBlockRenderPacket(position)));
                    for (net.minecraft.network.Connection conn : EUClient.PROXY_SERVER.getConnections()) {
                        if (conn.isConnected()) conn.send(packet);
                    }
                }
            }
        }

        return true;
    }

    public static boolean isPlaceable(BlockPos position) {
        return isPlaceable(position, false);
    }

    // An arrow stuck in/near the target cell has no placement-blocking collision in vanilla --
    // right-clicking a block with an arrow sticking out of it places fine (reported live: "vanilla
    // làm được thì client làm được"). AbstractArrow covers both Arrow and SpectralArrow.
    public static boolean isPlaceable(BlockPos position, boolean excludeSelf) {
        if (!mc.level.getBlockState(position).canBeReplaced()) return false;
        return mc.level.getEntities((Entity) null, new AABB(position), entity -> true).stream().noneMatch(entity -> !(entity instanceof EndCrystal) && !(entity instanceof ExperienceOrb) && !(entity instanceof ItemEntity) && !(entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) && !(entity.equals(mc.player) && excludeSelf));
    }

    public static boolean isCrystalPlaceable(BlockPos position) {
        if (!mc.level.getBlockState(position).canBeReplaced()) return false;
        return mc.level.getEntities((Entity) null, new AABB(position), entity -> true).stream().noneMatch(entity -> !(entity instanceof EndCrystal) && !(entity instanceof ExperienceOrb) && !(entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow));
    }

    // Hardness-0 blocks (flowers, mushrooms, tall grass, dead bush, saplings, ...) break in a
    // single hit regardless of tool/gamemode -- getDestroySpeed(level, pos) returns 0 for exactly
    // these. Used to let Surround/placement modules clear their own way instead of just skipping
    // the cell, matching "vanilla có thể phá rồi đặt thì client cũng phải làm được".
    public static boolean isInstantBreakable(BlockPos position) {
        BlockState state = mc.level.getBlockState(position);
        if (state.isAir() || state.canBeReplaced()) return false;
        return state.getDestroySpeed(mc.level, position) == 0.0f;
    }

    // Single-tick break attempt, no wait for the animation -- hardness-0 blocks break server-side
    // off the very first START_DESTROY_BLOCK regardless of tool, so STOP/swing right after is safe.
    // Same pattern as RekitModule.instantBreak.
    public static void instantBreak(BlockPos position, Direction direction) {
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction));
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    public static boolean destroyCrystals(BlockPos position) {
        return destroyCrystals(position, null, null, null);
    }

    // Checking only whether a crystal overlaps the TARGET position's own cell (the original
    // check) missed the actual failure mode reported live: the block never places even though
    // nothing sits on the target cell itself, because vanilla's server-side useItemOn handling
    // does its OWN raytrace from the player's eye to the claimed hit point and rejects the
    // interaction if that ray doesn't cleanly reach it -- a crystal standing ANYWHERE between the
    // eye and the target (not necessarily overlapping the target cell) blocks that raytrace and
    // gets the placement silently dropped server-side, with no client-visible error (the render-
    // preview ghost box, which is purely a client-side prediction added at the end of this SAME
    // method regardless of server acceptance, still shows -- exactly the reported "render hiện
    // nhưng không đặt được"). Search a box spanning the whole eye->target ray (when given) instead
    // of just the target cell, and only actually attack crystals whose hitbox the ray passes
    // through (AABB.clip), not just any crystal that happens to be somewhere nearby.
    private static boolean destroyCrystals(BlockPos position, Vec3 from, Vec3 to, Runnable retry) {
        AABB searchArea = from != null && to != null ? new AABB(from, to).inflate(0.5) : new AABB(position);

        List<Entity> surroundingCrystals;
        try {
            // placeBlock (and this) can run straight off SurroundModule.onPacketReceive, which
            // fires ON THE NETTY IO THREAD -- our packet-receive mixin posts the event before
            // vanilla's own dispatch hands off to the main thread. mc.level.getEntities() walks
            // EntitySection's live (non-thread-safe) ArrayLists; the main thread concurrently
            // spawning/despawning entities during that walk throws ConcurrentModificationException
            // and used to take the whole connection down. It's a transient timing collision, not
            // real corruption -- treat it as "nothing found this attempt" and let the caller's own
            // retry-on-next-confirm mechanism (see placeBlock) try again a moment later.
            surroundingCrystals = mc.level.getEntities((Entity) null, searchArea, entity -> entity instanceof EndCrystal
                    && (from == null || to == null || new AABB(position).intersects(entity.getBoundingBox()) || entity.getBoundingBox().clip(from, to).isPresent()));
        } catch (java.util.ConcurrentModificationException exception) {
            return false;
        }
        if (surroundingCrystals.isEmpty()) return false;

        // Attack ALL of them, not just the first -- a hole ring commonly has 2+ crystals
        // obstructing at once, and stopping after one meant only clearing a single crystal per
        // tick while AutoCrystal could place several. Rotate at each crystal before its attack
        // packet (rotation-checking anticheat drops attacks sent while looking at the block
        // instead of the entity) -- the block-facing rotation from the caller gets re-applied on
        // the retry tick once the crystal is actually gone.
        // No packetRotate here -- unlike block placement (server raytraces useItemOn against the
        // player's ACTUAL facing to build the hit result, so a fake look genuinely widens what's
        // reachable), vanilla/Grim's attack-reach check is pure distance, not look-angle. Faking a
        // rotation purely to attack a crystal buys nothing legitimate and only adds a rotation
        // snap right next to an attack packet -- exactly the pattern Grim's rotation checks (e.g.
        // RotationGS) are built to catch, which can get the ATTACK itself dropped even when it was
        // otherwise in range. Shoreline's own crystal-defense attack (referenced) sends no
        // rotation either -- matched here.
        for (Entity entity : surroundingCrystals) {
            mc.player.connection.send(new ServerboundAttackPacket(entity.getId()));
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            if (retry != null) EUClient.WORLD_MANAGER.onCrystalAttacked(entity.getId(), retry);
        }
        return true;
    }

    public static Vec3 getHitVector(BlockPos position, Direction direction) {
        return position.getCenter().add(direction.getStepX() / 2.0, direction.getStepY() / 2.0, direction.getStepZ() / 2.0);
    }

    public static Direction getClosestDirection(BlockPos position, boolean strictDirection) {
        if (strictDirection) {
            if (mc.player.getY() >= position.getY()) return Direction.UP;

            BlockHitResult result = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(position), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (result == null || result.getType() != HitResult.Type.BLOCK || result.getDirection() == null) {
                return getClosestDirection(position);
            }

            return result.getDirection();
        } else {
            return getClosestDirection(position);
        }
    }

    private static Direction getClosestDirection(BlockPos position) {
        Direction closestDirection = null;
        Vec3 offsetPosition = null;

        for (Direction direction : Direction.values()) {
            Vec3 newOffset = getHitVector(position, direction);
            if (closestDirection == null) {
                closestDirection = direction;
                offsetPosition = newOffset;
                continue;
            }

            if (mc.player.distanceToSqr(newOffset) < mc.player.distanceToSqr(offsetPosition)) {
                closestDirection = direction;
                offsetPosition = newOffset;
            }
        }

        return closestDirection;
    }

    public static Direction getDirection(BlockPos position, boolean strictDirection) {
        return getDirection(position, null, strictDirection);
    }

    public static Direction getDirection(BlockPos position, List<BlockPos> exceptions, boolean strictDirection) {
        List<Direction> strictDirections = new ArrayList<>();
        if (strictDirection) strictDirections = getStrictDirections(mc.player.getEyePosition(), Vec3.atCenterOf(position));

        Direction fallback = null;
        for (Direction direction : Direction.values()) {
            BlockPos offset = position.relative(direction);
            if (strictDirection && !strictDirections.contains(direction.getOpposite())) continue;
            if (mc.level.getBlockState(offset).canBeReplaced() && (exceptions == null || !exceptions.contains(offset))) {
                continue;
            }

            // Prefer non-interactable blocks to avoid opening containers
            if (RIGHT_CLICKABLE_BLOCKS.contains(mc.level.getBlockState(offset).getBlock())) {
                if (fallback == null) fallback = direction;
                continue;
            }

            return direction;
        }

        // Fall back to interactable block if no other option (sneak packet will handle it)
        return fallback;
    }

    public static List<Direction> getStrictDirections(Vec3 eyePos, Vec3 blockPos) {
        List<Direction> directions = new ArrayList<>();

        double differenceX = eyePos.x - blockPos.x;
        double differenceY = eyePos.y - blockPos.y;
        double differenceZ = eyePos.z - blockPos.z;

        if (differenceY > 0.5) {
            directions.add(Direction.UP);
        } else if (differenceY < -0.5) {
            directions.add(Direction.DOWN);
        } else {
            directions.add(Direction.UP);
            directions.add(Direction.DOWN);
        }

        if (differenceX > 0.5) {
            directions.add(Direction.EAST);
        } else if (differenceX < -0.5) {
            directions.add(Direction.WEST);
        } else {
            directions.add(Direction.EAST);
            directions.add(Direction.WEST);
        }

        if (differenceZ > 0.5) {
            directions.add(Direction.SOUTH);
        } else if (differenceZ < -0.5) {
            directions.add(Direction.NORTH);
        } else {
            directions.add(Direction.SOUTH);
            directions.add(Direction.NORTH);
        }

        return directions;
    }

    public static HitResult getRaytraceTarget(float yaw, float pitch, double x, double y, double z) {
        Vec3 rotationVector = new Vec3(Mth.sin(-yaw * 0.017453292F) * Mth.cos(pitch * 0.017453292F), -Mth.sin(pitch * 0.017453292F), Mth.cos(-yaw * 0.017453292F) * Mth.cos(pitch * 0.017453292F));
        HitResult result = mc.level.clip(new ClipContext(new Vec3(x, y, z), new Vec3(x + rotationVector.x * 5, y + rotationVector.y * 5, z + rotationVector.z * 5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        Vec3 vec3d = new Vec3(x, y + mc.player.getEyeHeight(mc.player.getPose()), z);
        double distance = 25;
        if (result != null) distance = result.getLocation().distanceToSqr(vec3d);

        Vec3 multipliedVector = vec3d.add(rotationVector.x * 5, rotationVector.y * 5, rotationVector.z * 5);
        AABB box = new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3).expandTowards(rotationVector.scale(5)).inflate(1.0, 1.0, 1.0);

        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(mc.player, vec3d, multipliedVector, box, (entity) -> !entity.isSpectator() && entity.isPickable(), distance);
        if (entityHitResult != null) {
            if (vec3d.distanceToSqr(entityHitResult.getLocation()) < distance || result == null) {
                if (entityHitResult.getEntity() instanceof LivingEntity) {
                    return entityHitResult;
                }
            }
        }

        return result;
    }

    public static boolean canSee(Entity entity) {
        return canSee(entity.getX(), entity.getY(), entity.getZ());
    }

    public static boolean canSee(BlockPos position)     {
        return canSee(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
    }

    public static boolean canSee(Vec3 vec3d) {
        return canSee(vec3d.x, vec3d.y, vec3d.z);
    }

    public static boolean canSee(double x, double y, double z) {
        return mc.level.clip(new ClipContext(new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()), new Vec3(x, y, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    // canSee(BlockPos) is unusable for a SOLID support block (e.g. AutoCrystal's obsidian): the
    // clip's own endpoint sits at that block's center, so the ray hits the block's own near face
    // first and returns BLOCK, never MISS -- "can I see this block" was unconditionally false for
    // every solid block, real wall or not (see AutoCrystalModule's own note on why it moved to
    // position.above() instead). But position.above() asks a DIFFERENT question -- "can I see the
    // empty air cell the crystal spawns into" -- which a real player never needs: vanilla lets you
    // place by clicking ANY reachable, visible face of the support block (its underside included),
    // same as standing under a ceiling and placing on the block right above your head. This checks
    // that instead: nothing OTHER than the block itself is in the way. If the clip hits a solid
    // block before reaching this one's own center, that hit block is a real obstruction (a wall) --
    // if the clip's own hit block position is `position` itself (or air), there's no wall.
    public static boolean canSeeBlock(BlockPos position) {
        HitResult result = mc.level.clip(new ClipContext(
                new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()),
                Vec3.atCenterOf(position), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

        if (result.getType() == HitResult.Type.MISS) return true;
        return result instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(position);
    }

    public static boolean canBreak(BlockPos... pos) {
        return Arrays.stream(pos).allMatch(blockPos -> mc.level.getBlockState(blockPos).getBlock().defaultDestroyTime() != -1);
    }

    public static boolean isReplaceable(BlockPos... pos) {
        return Arrays.stream(pos).allMatch(blockPos -> mc.level.getBlockState(blockPos).canBeReplaced());
    }

    public static Block getBlock(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock();
    }

    public static int getNetherPosition(int position) {
        return mc.player.level().dimension() == Level.NETHER ? position * 8 : position / 8;
    }

    public static String getMovementDirection(Direction direction) {
        if (direction.getName().equalsIgnoreCase("North")) return "-Z";
        if (direction.getName().equalsIgnoreCase("East")) return "+X";
        if (direction.getName().equalsIgnoreCase("South")) return "+Z";
        if (direction.getName().equalsIgnoreCase("West")) return "-X";
        return "N/A";
    }

    private static final String[] FACING_NAMES = {"South", "South West", "West", "North West", "North", "North East", "East", "South East"};
    private static final String[] FACING_AXES = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};

    // Direction.getName() only snaps to one of the 4 cardinal axes, so a diagonal yaw (e.g. facing
    // exactly between +X and -Z) reports just one of them. Bucket the raw yaw into 8 octants instead
    // so diagonal facings report both axis components (e.g. "+X -Z"), matching vanilla's own F3 debug
    // direction readout (which does the same 8-way "South West"/"North East" style naming).
    public static String getFacingName(float yaw) {
        return FACING_NAMES[getFacingOctant(yaw)];
    }

    public static String getFacingAxes(float yaw) {
        return FACING_AXES[getFacingOctant(yaw)];
    }

    private static int getFacingOctant(float yaw) {
        float angle = yaw % 360.0f;
        if (angle < 0) angle += 360.0f;
        return Math.round(angle / 45.0f) % 8;
    }

    public static float getBreakTime(Player player, BlockState blockState) {
        if (player == null) return 0.0f;

        float speed = NETHERITE_PICKAXE.getItem().getDestroySpeed(NETHERITE_PICKAXE, blockState) + 26;
        return (1.0f / (speed / blockState.getBlock().defaultDestroyTime() / 30)) * 50f;
    }

    public static double getBreakDelta(BlockState blockState, int slot) {
        if (slot == -1) return 0.0f;
        float speed = mc.player.getInventory().getItem(slot).getItem().getDestroySpeed(mc.player.getInventory().getItem(slot), blockState);

        if (speed > 1.0f) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            int efficiency = EnchantmentHelper.getItemEnchantmentLevel(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), stack);
            if (efficiency > 0 && !stack.isEmpty()) speed += efficiency * efficiency + 1;
        }

        if (MobEffectUtil.hasDigSpeed(mc.player)) speed *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2f;
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            speed *= (switch (mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            });
        }

        if (mc.player.isEyeInFluid(FluidTags.WATER) && !(EnchantmentHelper.getEnchantmentLevel(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.AQUA_AFFINITY), mc.player) > 0)) speed /= 5.0f;
        if (!mc.player.onGround()) speed /= 5.0f;

        return speed / blockState.getBlock().defaultDestroyTime() / (!blockState.requiresCorrectToolForDrops() || mc.player.getInventory().getItem(slot).isCorrectToolForDrops(blockState) ? 30 : 100);
    }

    public static float getMineSpeed(BlockState state, int slot) {
        if (mc.player == null) return 0;
        float speed = mc.player.getInventory().getItem(slot).getItem().getDestroySpeed(mc.player.getInventory().getItem(slot), state);

        if (speed > 1) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            int efficiency = EnchantmentHelper.getItemEnchantmentLevel(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), stack);
            if (efficiency > 0 && !stack.isEmpty()) speed += (float) (StrictMath.pow(efficiency, 2) + 1);
        }

        if (mc.player.hasEffect(MobEffects.HASTE)) speed *= 1 + (mc.player.getEffect(MobEffects.HASTE).getAmplifier() + 1) * 0.2F;
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) speed *= (float) Math.pow(0.3f, mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier() + 1);
        if (mc.player.isEyeInFluid(FluidTags.WATER)) speed *= (float) mc.player.getAttribute(Attributes.SUBMERGED_MINING_SPEED).getValue();
        if (!mc.player.onGround()) speed /= 5;

        speed = speed < 0 ? 0 : speed;
        return speed / state.getBlock().defaultDestroyTime() / (!state.requiresCorrectToolForDrops() || mc.player.getInventory().getItem(slot).isCorrectToolForDrops(state) ? 30 : 100);
    }

    public static boolean blocksMovement(BlockState state) {
        return state.getBlock() != Blocks.COBWEB && state.getBlock() != Blocks.BAMBOO_SAPLING && !state.canBeReplaced();
    }

    public static boolean equals(BlockPos x, BlockPos y) {
        if(x == null && y == null) {
            return true;
        } else if(x == null || y == null) {
            return false;
        } else {
            return x.equals(y);
        }
    }

    public static String getDimension() {
        return mc.player.level().dimension().identifier().toString().replace("minecraft:", "");
    }

    public static List<Player> getCollisions(BlockPos pos) {
        List<Player> collisions = new ArrayList<>();
        for(Player player : mc.level.players()) {
            if(player == null || player.isRemoved()) continue;
            if(player.getBoundingBox().intersects(new AABB(pos))) collisions.add(player);
        }
        return collisions;
    }
}
