package eu.client.pingbypass.modules.submodules.crystal;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.impl.combat.SuicideModule;
import eu.client.modules.impl.player.SpeedMineModule;
import eu.client.modules.impl.player.KeyActionModule;
import eu.client.pingbypass.modules.PbModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.*;
import eu.client.utils.IMinecraft;
import eu.client.utils.minecraft.DamageUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.minecraft.PositionUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.Counter;
import eu.client.utils.system.Timer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Proxy-only AutoCrystal, ported from AutoCrystalModule's isRunningOnProxy()-guarded
 * branches with the guards removed entirely -- this class only ever runs on the proxy's
 * own LocalPlayer (mc.player, via IMinecraft.mc), so there is no second execution context
 * to branch on. Render-only settings (color/animation/icon) are dropped: the proxy has no
 * GUI to render for itself, it just forwards the raw target position via
 * EUClient.RENDER_MANAGER.setRenderPosition(...) so the real client can render it.
 */
public class ServerAutoCrystal extends PbModule implements IMinecraft {
    public BooleanSetting attack = new BooleanSetting("Attack", "Automatically attacks crystals that are deemed safe.", true);
    public NumberSetting attackSpeed = new NumberSetting("AttackSpeed", "The speed at which crystals will be attacked.", 20.0f, 0.1f, 20.0f);
    public NumberSetting attackRange = new NumberSetting("AttackRange", "The maximum distance at which crystals will be attacked.", 4.5, 0.0, 8.0);
    public NumberSetting attackWallsRange = new NumberSetting("AttackWallsRange", "The maximum distance at which crystals will be attacked through walls.", 4.5, 0.0, 8.0);
    public ModeSetting antiWeakness = new ModeSetting("AntiWeakness", "Allows you to attack crystals when weaknessed.", "None", new String[]{"None", "Normal", "Silent"});
    public BooleanSetting instant = new BooleanSetting("Instant", "Instantly attacks crystals once they spawn.", true);
    public BooleanSetting inhibit = new BooleanSetting("Inhibit", "Prevents excessive attacks on crystals by blacklisting crystals when attacking them.", true);

    public BooleanSetting place = new BooleanSetting("Place", "Automatically places crystals on positions that are deemed safe and lethal enough.", true);
    public NumberSetting placeSpeed = new NumberSetting("PlaceSpeed", "The speed at which crystals will be placed.", 20.0f, 0.1f, 20.0f);
    public NumberSetting placeRange = new NumberSetting("PlaceRange", "The maximum distance at which positions will be placed on.", 4.5, 0.0, 8.0);
    public NumberSetting placeWallsRange = new NumberSetting("PlaceWallsRange", "The maximum distance at which positions will be placed on through walls.", 4.5, 0.0, 8.0);
    public ModeSetting placements = new ModeSetting("Placements", "The version of the game that will be used for crystal placement calculations.", "Native", new String[]{"Native", "Protocol"});
    public BooleanSetting blockDestruction = new BooleanSetting("BlockDestruction", "Places crystals on top of mined blocks in order to damage opponents.", true);
    public ModeSetting autoSwitch = new ModeSetting("Switch", "Automatically switches to a crystal if you aren't currently holding one.", "None", new String[]{"None", "Normal", "Silent", "AltSwap"});
    public BooleanSetting swapBack = new BooleanSetting("SwapBack", "Switches back to the item you were holding before the module started switching to crystals.", false);
    public NumberSetting swapDelay = new NumberSetting("SwapDelay", "The delay in ticks after swapping before placing or attacking crystals.", 0, 0, 20);

    public ModeSetting sequential = new ModeSetting("Sequential", "The sequence that the module's processes will be run in.", "Strong", new String[]{"None", "Strict", "Strong"});
    // 2026-08-14: reverted to master + diffed against bản gốc 1.21.4 (Desktop copy), same as
    // AutoCrystalModule/SpeedMineModule -- MovementSync removed (invented within this session's
    // branch, never merged, no bản gốc equivalent). "Normal" is a deliberate no-op here (see
    // setYawPitch's doc: this class only ever runs on the proxy, where bản gốc's "Normal" never
    // touched the camera either); Packet/Silent's call site is unchanged (wireRotate(mode, rots),
    // fired for every mode except None -- see RotationManager.wireRotate's own doc for Silent).
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the crystal whenever attacking or placing.", "Normal", new String[]{"None", "Normal", "Packet", "Silent"});
    public ModeSetting swing = new ModeSetting("Swing", "The hand that will be used for swinging.", "Default", new String[]{"Default", "None", "Packet", "Mainhand", "Offhand", "Both"});
    public BooleanSetting yawStep = new BooleanSetting("YawStep", "Performs your rotations over multiple ticks.", false);
    public NumberSetting yawStepThreshold = new NumberSetting("YawStepThreshold", "The threshold in order for yaw to be modified.", 75, 1, 180);
    public BooleanSetting raytrace = new BooleanSetting("Raytrace", "Avoids attacking or placing any crystals through walls.", false);
    public NumberSetting extrapolation = new NumberSetting("Extrapolation", "Extrapolates the target's position to calculate positions ahead of time.", 0, 0, 20);
    public NumberSetting enemyRange = new NumberSetting("EnemyRange", "The maximum distance at which enemies can be at.", 10.0, 0.0, 24.0);
    public BooleanSetting chestBreak = new BooleanSetting("ChestBreak", "Prevents other players from getting obsidian from ender chests by destroying the dropped items.", false);
    public BooleanSetting gameLoop = new BooleanSetting("GameLoop", "Runs the module on loop instead of ticks.", false);
    public NumberSetting loopDelay = new NumberSetting("LoopDelay", "The delay that has to be waited out before running the module again.", 50, 0, 1000);
    public ModeSetting whileEating = new ModeSetting("WhileEating", "Places and attacks crystal while eating or using items.", "Both", new String[]{"None", "Attack", "Place", "Both"});

    public BooleanSetting godSync = new BooleanSetting("GodSync", "Makes the attacking way faster by predicting entity IDs.", false);
    public NumberSetting predictions = new NumberSetting("Predictions", "The amount of predictions that will be done after placing.", 10, 1, 20);
    public NumberSetting offset = new NumberSetting("Offset", "The amount that the last entity ID should be offset by.", 0, 0, 2);
    public ModeSetting godSwing = new ModeSetting("GodSwing", "The swinging that will be done for each predicted attack.", "Normal", new String[]{"None", "Normal", "Strict"});
    public BooleanSetting fast = new BooleanSetting("Fast", "Improves the speed of the prediction calculations at the cost of stability.", false);
    public BooleanSetting antiKick = new BooleanSetting("AntiKick", "Prevents you from getting kicked by attacking invalid entity IDs.", false);
    public NumberSetting kickThreshold = new NumberSetting("KickThreshold", "The tick threshold for the kick prevention.", 5, 1, 10);

    public ModeSetting facePlaceMode = new ModeSetting("FaceplaceMode", "The checks that will be done in order to faceplace.", "Dynamic", new String[]{"None", "Dynamic", "Always"});
    public ModeSetting facePlaceSpeed = new ModeSetting("FaceplaceSpeed", "The speed that players will be faceplaced at.", "Normal", new String[]{"Normal", "Custom"});
    public NumberSetting facePlaceDelay = new NumberSetting("FaceplaceDelay", "The ticks that have to be waited for before faceplacing again.", 11, 0, 20);
    public BooleanSetting healthPlace = new BooleanSetting("HealthPlace", "Whether or not to faceplace when the target's health is low.", true);
    public NumberSetting health = new NumberSetting("Health", "The health that the target needs to be at in order for the module to start faceplacing.", 8.0f, 0.0f, 36.0f);
    public BooleanSetting armorPlace = new BooleanSetting("ArmorPlace", "Whether or not to faceplace when the target's armor is low on durability.", true);
    public NumberSetting percentage = new NumberSetting("Percentage", "The percentage that one of the target's armor pieces need to be at in order to start faceplacing.", 10, 1, 100);

    public NumberSetting minimumDamage = new NumberSetting("MinimumDamage", "The minimum damage that has to be dealt to enemies.", 6.0, 0.0, 36.0);
    public NumberSetting maximumSelfDamage = new NumberSetting("MaximumSelfDamage", "The maximum damage that can be dealt to you by crystals.", 10.0, 0.0, 36.0);
    public NumberSetting lethalMultiplier = new NumberSetting("LethalMultiplier", "The amount of crystals that the target has to be killed by in order to ignore minimum damage.", 1.5f, 0.0f, 4.0f);
    public BooleanSetting antiSuicide = new BooleanSetting("AntiSuicide", "Prevents crystals from accidentally killing you when you're low on health.", true);
    public BooleanSetting ignoreTerrain = new BooleanSetting("IgnoreTerrain", "Ignores terrain that can be destroyed when calculating damage.", true);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> pendingCalc = null;

    private Runnable attackRunnable = null;
    private Runnable placeRunnable = null;

    private final Map<Integer, Long> attackedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> placedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> countedCrystals = new ConcurrentHashMap<>();

    private final Timer attackTimer = new Timer();
    private final Timer placeTimer = new Timer();
    private final Timer facePlaceTimer = new Timer();
    private final Timer loopTimer = new Timer();
    private final Timer swapTimer = new Timer();
    private int lastSelectedSlot = -1;
    private long totalPlaces = 0;
    private long totalAttacks = 0;

    private boolean sequenceAttack = false;
    private boolean sequencePlace = true;
    private boolean attackedSequentially = false;
    private boolean placedSequentially = false;

    @Getter private Player target = null;

    private EndCrystal attackTarget = null;
    private PlaceTarget placeTarget = null;
    private PlaceTarget mineTarget = null;

    private String calculationTime = "0.00ms";
    private int calculationCount = 0;
    @Getter private String calculationDamage = "0.00";

    private final Counter crystalCounter = new Counter();
    private int crystalsPerSecond = 0;

    private int highestID = -100000;
    private int kickTicks = 0;
    private int savedSlot = -1;

    public ServerAutoCrystal() {
        super("AutoCrystal");
    }

    @Override
    public void onEnable() {
        EUClient.EVENT_HANDLER.subscribe(this);
    }

    @Override
    public void onDisable() {
        EUClient.EVENT_HANDLER.unsubscribe(this);

        if (pendingCalc != null) {
            pendingCalc.cancel(false);
            pendingCalc = null;
        }

        if (savedSlot != -1) {
            InventoryUtils.switchBackNormal(savedSlot);
            savedSlot = -1;
        }

        attackRunnable = null;
        placeRunnable = null;

        EUClient.RENDER_MANAGER.setRenderPosition(null);

        attackedCrystals.clear();
        placedCrystals.clear();
        countedCrystals.clear();

        attackedSequentially = false;
        placedSequentially = false;

        target = null;
        placeTarget = null;
        mineTarget = null;

        calculationTime = "0.00ms";
        calculationCount = 0;
        calculationDamage = "0.00";

        crystalCounter.reset();
        highestID = -100000;
    }

    @Override
    public List<Setting> getSettings() {
        return List.of(attack, attackSpeed, attackRange, attackWallsRange, antiWeakness, instant, inhibit,
                place, placeSpeed, placeRange, placeWallsRange, placements, blockDestruction, autoSwitch, swapBack, swapDelay,
                sequential, rotate, swing, yawStep, yawStepThreshold, raytrace, extrapolation, enemyRange,
                chestBreak, gameLoop, loopDelay, whileEating,
                godSync, predictions, offset, godSwing, fast, antiKick, kickThreshold,
                facePlaceMode, facePlaceSpeed, facePlaceDelay, healthPlace, health, armorPlace, percentage,
                minimumDamage, maximumSelfDamage, lethalMultiplier, antiSuicide, ignoreTerrain);
    }

    private boolean isDead() {
        if (mc.player == null || mc.level == null) return true;
        if (!mc.player.isAlive() || mc.player.isDeadOrDying() || mc.player.getHealth() <= 0.0f) return true;
        if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) return true;
        return false;
    }

    /** Called once per proxy tick (see PbModuleManager.tick(), driven by ProxyServerTickListener). */
    @Override
    public void tick() {
        if (isDead()) {
            attackRunnable = null;
            placeRunnable = null;
            target = null;
            placeTarget = null;
            attackTarget = null;
            mineTarget = null;
            placedCrystals.clear();
            attackedCrystals.clear();
            countedCrystals.clear();
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (lastSelectedSlot != -1 && lastSelectedSlot != currentSlot) {
            swapTimer.reset();
        }
        lastSelectedSlot = currentSlot;

        long minTtl = 50L;
        attackedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, minTtl));
        long placedTtl = Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, 500L)
                + (long) ((20 - attackSpeed.getValue().floatValue()) * 50L);
        placedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > placedTtl);
        countedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, minTtl));

        crystalsPerSecond = crystalCounter.getCount();

        Runnable runnable = () -> {
            long startTime = System.nanoTime();

            attackTarget = calculateCrystals();
            placeTarget = calculatePlacements(null);

            long calcNanos = System.nanoTime() - startTime;
            calculationTime = new DecimalFormat("0.00").format(calcNanos / 1000000.0) + "ms";
            calculationCount = placeTarget == null ? 0 : placeTarget.getCalculations();
            calculationDamage = placeTarget == null ? "0.00" : new DecimalFormat("0.00").format(placeTarget.getDamage());

            target = placeTarget == null ? null : placeTarget.getPlayer();

            if (blockDestruction.getValue()) {
                SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
                BlockPos position = null;

                if (module.getPrimary() != null && module.getPrimary().isMining()) position = module.getPrimary().getPosition();
                if (position != null) mineTarget = calculatePlacements(position);
            }
        };

        if (pendingCalc == null || pendingCalc.isDone()) pendingCalc = executor.submit(runnable);

        if (gameLoop.getValue()) {
            if (!loopTimer.hasTimeElapsed(loopDelay.getValue().longValue())) return;
            loopTimer.reset();
        }

        run();

        if (attackRunnable != null) {
            attackRunnable.run();
            attackRunnable = null;
        }
        if (placeRunnable != null) {
            placeRunnable.run();
            placeRunnable = null;
        }
    }

    private void run() {
        if (isDead()) return;
        attackRunnable = null;
        placeRunnable = null;

        if (sequential.getValue().equalsIgnoreCase("None")) {
            if (sequenceAttack) {
                sequenceAttack = false;
                sequencePlace = true;

                attackCrystals();
                return;
            }

            if (sequencePlace) {
                sequenceAttack = true;
                sequencePlace = false;

                placeCrystals(false);
            }
        } else {
            if (attack.getValue()) attackCrystals();
            if (place.getValue()) placeCrystals(false);
        }
    }

    @SubscribeEvent
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (isDead() || getPlayers().isEmpty()) return;

        if (!attack.getValue() || !instant.getValue()) return;
        if (!attackTimer.hasTimeElapsed(1000.0f - attackSpeed.getValue().floatValue() * 50.0f))
            return;

        if (!(event.getEntity() instanceof EndCrystal crystal)) return;

        if (inhibit.getValue() && attackedCrystals.containsKey(crystal.getId())) return;
        if (!placedCrystals.containsKey(crystal.blockPosition().below())) return;
        if (crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) return;
        if (!mc.level.getWorldBorder().isWithinBounds(crystal.blockPosition())) return;
        if (!WorldUtils.canSee(crystal) && (raytrace.getValue() || crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackWallsRange.getValue().doubleValue())))
            return;

        if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));
        // "Normal" is a deliberate no-op here -- see setYawPitch's doc, this class only ever
        // runs on the proxy, where bản gốc's "Normal" never touched the camera either.

        attack(crystal);

        attackedSequentially = true;
        if (sequential.getValue().equalsIgnoreCase("Strong")) {
            placeCrystals(true);
        }
    }

    @SubscribeEvent
    public void onDestroyBlock(DestroyBlockEvent event) {
        if (isDead() || getPlayers().isEmpty()) return;

        kickTicks = 0;

        if (!blockDestruction.getValue()) return;
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;

        BlockPos minedPosition = event.getPosition();
        if (minedPosition == null) return;

        int slot = InventoryUtils.findHotbar(Items.END_CRYSTAL);
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean switched = false;

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1 && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL))
            return;

        PlaceTarget mineTarget = this.mineTarget == null ? null : this.mineTarget.clone();
        if (mineTarget == null || (mineTarget.getPosition() != null && !minedPosition.equals(mineTarget.getException()))) mineTarget = calculatePlacements(minedPosition);
        if (mineTarget == null || mineTarget.getPosition() == null) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        BlockPos position = mineTarget.getPosition();

        // See AutoCrystalModule's identical 2026-08-15 fix -- render position only committed once
        // confirmed still in range, so a stale (async-computed, since-moved-away-from) target
        // never draws a ghost placement out at its old distance.
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        EUClient.RENDER_MANAGER.setRenderPosition(position);

        if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue())))
            return;

        if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(position).add(0, 1, 0)));

        for (Entity entity : mc.level.getEntities((Entity) null, new AABB(position.above()), entity -> true).stream().filter(entity -> entity instanceof EndCrystal).toList()) {
            if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(entity));

            mc.player.connection.send(new ServerboundAttackPacket(entity.getId()));
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

            break;
        }

        SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
        boolean flag = module.switchReset.getValue() && (module.switchMode.getValue().equalsIgnoreCase("Normal") || module.switchMode.getValue().equalsIgnoreCase("AltSwap") || module.switchMode.getValue().equalsIgnoreCase("AltPickup"));

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
            if (!flag && autoSwitch.getValue().equalsIgnoreCase("Normal") && swapBack.getValue() && savedSlot == -1) savedSlot = previousSlot;
            InventoryUtils.switchSlot(flag ? "AltSwap" : autoSwitch.getValue(), slot, previousSlot);
            switched = true;
        }

        place(position);

        if (switched) {
            InventoryUtils.switchBack(flag ? "AltSwap" : autoSwitch.getValue(), slot, previousSlot);
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (event.getPacket() instanceof ClientboundAddEntityPacket packet) {
            if (packet.getId() > highestID) highestID = packet.getId();

            BlockPos position = BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()).offset(0, -1, 0);
            if (countedCrystals.containsKey(position)) {
                if (facePlaceTimer.hasTimeElapsed(facePlaceDelay.getValue().longValue() * 50L)) facePlaceTimer.reset();

                countedCrystals.remove(position);

                crystalCounter.increment();
                crystalsPerSecond = crystalCounter.getCount();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        kickTicks = 0;
        attackRunnable = null;
        placeRunnable = null;
        target = null;
        placeTarget = null;
        attackTarget = null;
        mineTarget = null;
        placedCrystals.clear();
        attackedCrystals.clear();
        countedCrystals.clear();
        EUClient.RENDER_MANAGER.setRenderPosition(null);
    }

    @SubscribeEvent
    public void onClientConnect(ClientConnectEvent event) {
        highestID = -100000;
    }

    public String getMetaData() {
        return calculationTime + ", " + calculationCount + ", " + calculationDamage + ", " + crystalsPerSecond;
    }


    private void attackCrystals() {
        if (isDead()) return;
        if (getPlayers().isEmpty()) {
            attackTarget = null;
            return;
        }

        EndCrystal overrideCrystal = null;

        // obstructions accumulates EVERY blocked candidate scanned, not just whatever's in the
        // way of the spot we actually ended up placing at -- if a clear position was found
        // elsewhere (getPosition() != null, meaning we already have our own crystal down and
        // ready to detonate), a leftover obstruction from some other, unrelated candidate spot
        // must not hijack the attack away from our own placed crystal. Only treat obstructions
        // as attack-worthy when they're the reason NO placement could be made at all.
        PlaceTarget pt = this.placeTarget;
        boolean flag = pt != null && pt.getPosition() == null && pt.obstructions != null && !pt.obstructions.isEmpty();
        for (Entity entity : flag ? pt.obstructions : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) continue;
            if (!crystal.isAlive()) continue;
            if (inhibit.getValue() && attackedCrystals.containsKey(entity.getId())) continue;
            if (!flag && !placedCrystals.containsKey(crystal.blockPosition().below())) continue;
            if (crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(crystal.blockPosition())) continue;
            if (!WorldUtils.canSee(crystal) && (raytrace.getValue() || crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackWallsRange.getValue().doubleValue())))
                continue;

            overrideCrystal = crystal;
            break;
        }

        EndCrystal crystal = overrideCrystal == null ? attackTarget : overrideCrystal;
        if (crystal == null) return;

        // "Normal" is a deliberate no-op here -- see setYawPitch's doc, this class only ever
        // runs on the proxy, where bản gốc's "Normal" never touched the camera either.

        if (swapDelay.getValue().intValue() > 0 && !swapTimer.hasTimeElapsed(swapDelay.getValue().longValue() * 50L)) return;
        if (!attackTimer.hasTimeElapsed(1000.0f - attackSpeed.getValue().floatValue() * 50.0f) || attackedSequentially) {
            if (attackedSequentially) attackedSequentially = false;
            return;
        }

        Entity entity = mc.level.getEntity(crystal.getId());
        String bailReason = null;
        if (entity == null) bailReason = "entity-gone";
        else if (!(entity instanceof EndCrystal)) bailReason = "not-end-crystal";
        else if (!((EndCrystal) entity).isAlive()) bailReason = "dead";
        else if (inhibit.getValue() && attackedCrystals.containsKey(entity.getId())) bailReason = "inhibit";
        else if (entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) bailReason = "range";
        else if (!mc.level.getWorldBorder().isWithinBounds(entity.blockPosition())) bailReason = "border";
        else if (!WorldUtils.canSee(entity) && (raytrace.getValue() || entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackWallsRange.getValue().doubleValue())))
            bailReason = "cannot-see";

        if (bailReason != null) return;

        attackRunnable = () -> {
            if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));

            attack(crystal);
        };
    }

    private void placeCrystals(boolean sequential) {
        if (isDead()) return;
        PlaceTarget placeTarget = this.placeTarget == null ? null : this.placeTarget.clone();
        if (placeTarget == null || placeTarget.getPosition() == null) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        if (placeTarget.getPlayer() == null || !placeTarget.getPlayer().isAlive() || placeTarget.getPlayer().isDeadOrDying() || placeTarget.getPlayer().getHealth() <= 0.0f) {
            this.placeTarget = null;
            this.target = null;
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        int slot = InventoryUtils.findHotbar(Items.END_CRYSTAL);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1 && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL))
            return;

        BlockPos position = placeTarget.getPosition();

        // See AutoCrystalModule's identical 2026-08-15 fix for the full rationale.
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        EUClient.RENDER_MANAGER.setRenderPosition(position);

        if (!mc.level.getWorldBorder().isWithinBounds(position)) return;
        if (mc.level.getBlockState(position).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(position).getBlock() != Blocks.BEDROCK) return;
        if (!mc.level.getBlockState(position.offset(0, 1, 0)).isAir() || (placements.getValue().equalsIgnoreCase("Protocol") && !mc.level.getBlockState(position.offset(0, 2, 0)).isAir())) return;
        if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue()))) return;
        if (mc.level.getEntities((Entity) null, new AABB(position.offset(0, 1, 0)), entity -> true).stream().anyMatch(entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal))) return;

        if (swapDelay.getValue().intValue() > 0 && !swapTimer.hasTimeElapsed(swapDelay.getValue().longValue() * 50L)) return;
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;
        if (!sequential && placedSequentially) {
            placedSequentially = false;
            return;
        }

        placeRunnable = () -> {
            boolean switched = false;

            if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(position).add(0, 1, 0)));

            if (mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
                if (autoSwitch.getValue().equalsIgnoreCase("Normal") && swapBack.getValue() && savedSlot == -1) savedSlot = previousSlot;
                InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
                switched = true;
            }

            place(position);

            if (switched) {
                InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);
            }

            if (godSync.getValue()) {
                boolean flag = !antiKick.getValue() || !(mc.player.getMainHandItem().getItem() instanceof ExperienceBottleItem) && !(mc.player.getOffhandItem().getItem() instanceof ExperienceBottleItem) && !EUClient.MODULE_MANAGER.getModule(KeyActionModule.class).isXpActive();
                if ((!antiKick.getValue() || kickTicks > kickThreshold.getValue().intValue()) && flag) {
                    if (!fast.getValue()) {
                        for (Entity entity : mc.level.entitiesForRendering()) {
                            if (entity.getId() <= highestID) continue;
                            highestID = entity.getId();
                        }
                    }

                    for (int i = 1 - offset.getValue().intValue(); i < predictions.getValue().intValue(); ++i) {
                        Entity entity = mc.level.getEntity(highestID);
                        if (entity == null || entity instanceof EndCrystal) {
                            int id = highestID + i;

                            mc.getConnection().send(new ServerboundAttackPacket(id));
                            if (godSwing.getValue().equals("Strict")) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

                            attackedCrystals.put(id, System.currentTimeMillis());
                        }
                    }

                    if (godSwing.getValue().equals("Normal")) mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }

                kickTicks++;
            }
        };

        if (sequential) {
            placeRunnable.run();
            placeRunnable = null;

            placedSequentially = true;
        }
    }

    private EndCrystal calculateCrystals() {
        if (!attack.getValue()) return null;
        if (shouldPause("Attack")) return null;

        List<Player> players = getPlayers();
        if (players.isEmpty()) return null;

        EndCrystal optimalCrystal = null;
        float optimalDamage = 0.0f;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) continue;
            if (!crystal.isAlive()) continue;
            if (inhibit.getValue() && attackedCrystals.containsKey(entity.getId())) continue;
            if (crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(crystal.blockPosition())) continue;
            if (!WorldUtils.canSee(crystal) && (raytrace.getValue() || crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackWallsRange.getValue().doubleValue())))
                continue;

            if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
                float damage = DamageUtils.getCrystalDamage(mc.player, null, crystal, ignoreTerrain.getValue());
                if (damage > maximumSelfDamage.getValue().floatValue()) continue;
                if (antiSuicide.getValue() && damage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
            }

            boolean override = false;
            for (Player player : players) {
                float damage = DamageUtils.getCrystalDamage(player, PositionUtils.extrapolate(player, extrapolation.getValue().intValue()), crystal, ignoreTerrain.getValue());
                if (damage < getMinimumDamage(player, minimumDamage.getValue().floatValue()) && damage < player.getHealth() + player.getAbsorptionAmount() && !(damage * (1.0f + lethalMultiplier.getValue().floatValue()) >= player.getHealth() + player.getAbsorptionAmount()))
                    continue;

                if (damage > optimalDamage || damage > player.getHealth() + player.getAbsorptionAmount()) {
                    optimalCrystal = crystal;
                    optimalDamage = damage;

                    if (damage > player.getHealth() + player.getAbsorptionAmount()) {
                        override = true;
                        break;
                    }
                }
            }

            if (override) break;
        }

        return optimalCrystal;
    }

    // See calculatePlacements' own comment on stickyPosition. Comfortably above float noise from
    // recomputing DamageUtils.getCrystalDamage each cycle, comfortably below any damage delta that
    // should actually matter for picking a spot.
    private static final float STICKY_EPSILON = 0.5f;

    private PlaceTarget calculatePlacements(BlockPos exception) {
        if (!place.getValue()) return null;

        if (shouldPause("Place") || ((autoSwitch.getValue().equalsIgnoreCase("None") || InventoryUtils.findHotbar(Items.END_CRYSTAL) == -1) && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL)))
            return null;

        List<Player> players = getPlayers();
        if (players.isEmpty()) return null;

        BlockPos optimalPosition = null;
        Player optimalPlayer = null;
        List<Entity> obstructions = new ArrayList<>();
        float optimalDamage = 0.0f;

        // Hysteresis for the tied/near-tied case: two candidates sitting at genuinely equal (or
        // near-equal, floating point) damage each cycle re-picked whichever happened to compute
        // marginally higher THIS tick -- as the target moves even slightly, damage recalculates
        // fresh every cycle and the two candidates trade the lead back and forth, so the module
        // kept re-committing to a new position every other tick instead of just placing (reported:
        // "ngập ngừng, giảm speed" alternating between two X-marked spots both reading 108.0
        // damage). A new candidate only displaces the position already committed to last cycle if
        // it's actually better by more than float noise -- ties stay put.
        BlockPos stickyPosition = this.placeTarget == null ? null : this.placeTarget.getPosition();
        int calculations = 0;

        // Matches the known-good pre-port (1.21.4) implementation byte-for-byte: self-origin,
        // nearest-first, break on the first lethal candidate. Two separate "improvements" were
        // tried here this session (origin-from-target with a self-damage tiebreak, then
        // origin-from-target with a same-distance-tier damage comparison) and BOTH still
        // reproduced the exact same misplacement the user reported, on the same test case the
        // pre-port version handles correctly with this plain algorithm -- so the search structure
        // itself was never the actual bug. Reverted back to it; the real defect is elsewhere
        // (damage calc / gating), not in how candidates are ordered or when the scan stops.
        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(Math.max(placeRange.getValue().doubleValue(), placeWallsRange.getValue().doubleValue())); i++) {
            BlockPos position = mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i));

            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(position)) continue;
            if (mc.level.getBlockState(position).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(position).getBlock() != Blocks.BEDROCK) continue;
            if (!mc.level.getBlockState(position.offset(0, 1, 0)).isAir() || (placements.getValue().equalsIgnoreCase("Protocol") && !mc.level.getBlockState(position.offset(0, 2, 0)).isAir())) continue;

            if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue()))) continue;

            if (mc.level.getEntities((Entity) null, new AABB(position.offset(0, 1, 0)), entity -> true).stream().anyMatch(entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal))) continue;

            // A crystal WE just placed here needs a moment to actually land its hit before we
            // treat it as "in the way" and attack it ourselves (the tickCount grace period, keyed
            // off attackSpeed). Any OTHER crystal already sitting here -- one we didn't place,
            // already past its own attack window, or just pre-existing -- isn't waiting on
            // anything and should count as an obstruction immediately. Without this distinction,
            // calculatePlacements kept skipping this position for the full grace window every
            // cycle, repeatedly searching for (and often failing to find) somewhere else instead
            // of just clearing the crystal actually blocking the best spot.
            List<Entity> obstructingCrystals = mc.level.getEntities((Entity) null, new AABB(position.offset(0, 1, 0)), entity -> true).stream().filter(entity -> entity instanceof EndCrystal crystal
                    && (!placedCrystals.containsKey(crystal.blockPosition().below()) || crystal.tickCount >= (20 - attackSpeed.getValue().intValue()) + 15)).toList();

            if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
                float selfDamage = DamageUtils.getCrystalDamage(mc.player, null, position, exception, ignoreTerrain.getValue());
                if (selfDamage > maximumSelfDamage.getValue().floatValue()) continue;
                if (antiSuicide.getValue() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
            }

            boolean override = false;
            for (Player player : players) {
                calculations++;

                float damage = DamageUtils.getCrystalDamage(player, PositionUtils.extrapolate(player, extrapolation.getValue().intValue()), position, exception, ignoreTerrain.getValue());
                if (damage < getMinimumDamage(player, minimumDamage.getValue().floatValue()) && damage < player.getHealth() + player.getAbsorptionAmount() && !(damage * (1.0f + lethalMultiplier.getValue().floatValue()) >= player.getHealth() + player.getAbsorptionAmount()))
                    continue;

                if (exception == null && !obstructingCrystals.isEmpty()) {
                    obstructions.add(obstructingCrystals.getFirst());
                    break;
                }

                // Boost, not a separate branch: applies whichever order the two tied candidates
                // get scanned in (sticky found first stays ahead of a marginally-higher
                // challenger; sticky found second still overtakes an already-set non-sticky
                // optimal). Only affects the tie-break threshold -- optimalDamage carries the
                // boosted value forward too, which is fine, that's the whole point (a THIRD
                // candidate needs to clear the same bar to unseat the sticky choice, not just
                // edge out its raw damage).
                float comparisonDamage = damage + (position.equals(stickyPosition) ? STICKY_EPSILON : 0.0f);
                if (comparisonDamage > optimalDamage || damage > player.getHealth() + player.getAbsorptionAmount()) {
                    optimalPosition = position;
                    optimalPlayer = player;
                    optimalDamage = comparisonDamage;

                    if (damage > player.getHealth() + player.getAbsorptionAmount()) {
                        override = true;
                        break;
                    }
                }
            }

            if (override) break;
        }

        if (optimalPosition == null) return new PlaceTarget(null, null, obstructions, null, 0.0f, calculations);
        return new PlaceTarget(optimalPosition, optimalPlayer, obstructions, exception, optimalDamage, calculations);
    }

    private float[] calculateRotations(Vec3 vec3d) {
        float[] rotations = RotationUtils.getRotations(vec3d);

        if (yawStep.getValue()) {
            float yaw;

            float difference = EUClient.ROTATION_MANAGER.getServerYaw() - rotations[0];
            if (Math.abs(difference) > 180.0f) difference += difference > 0.0f ? -360.0f : 360.0f;

            float deltaYaw = (difference > 0.0f ? -1 : 1) * yawStepThreshold.getValue().floatValue();

            if (Math.abs(difference) > yawStepThreshold.getValue().floatValue()) yaw = EUClient.ROTATION_MANAGER.getServerYaw() + deltaYaw;
            else yaw = rotations[0];

            rotations[0] = yaw;
        }

        return rotations;
    }

    private void attack(EndCrystal crystal) {
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        int switchedSlot = -1;

        if (!antiWeakness.getValue().equalsIgnoreCase("None") && mc.player.hasEffect(MobEffects.WEAKNESS)) {
            int slot = InventoryUtils.findBestSword(InventoryUtils.HOTBAR_START, InventoryUtils.HOTBAR_END);
            if (slot != -1) {
                InventoryUtils.switchSlot(antiWeakness.getValue(), slot, previousSlot);
                switchedSlot = slot;
            }
        }

        mc.getConnection().send(new ServerboundAttackPacket(crystal.getId()));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        // See AutoCrystalModule.attack()'s own note -- same hardcoded-0 switchBack key bug.
        if (switchedSlot != -1) {
            InventoryUtils.switchBack(antiWeakness.getValue(), switchedSlot, previousSlot);
        }

        attackedCrystals.put(crystal.getId(), System.currentTimeMillis());
        attackTimer.reset();
        totalAttacks++;
    }

    private void place(BlockPos position) {
        InteractionHand hand = mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        
        eu.client.modules.impl.combat.CrystalPlacementHelper.PlacementResult placement = eu.client.modules.impl.combat.CrystalPlacementHelper.getVisiblePlacement(position);

        NetworkUtils.sendSequencedPacket(sequence ->
                new ServerboundUseItemOnPacket(hand, new BlockHitResult(placement.hitVec, placement.direction, position, false), sequence));

        switch (swing.getValue()) {
            case "Default" -> mc.player.swing(hand);
            case "Packet" -> mc.getConnection().send(new ServerboundSwingPacket(hand));
            case "Mainhand" -> mc.player.swing(InteractionHand.MAIN_HAND);
            case "Offhand" -> mc.player.swing(InteractionHand.OFF_HAND);
            case "Both" -> {
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.OFF_HAND);
            }
        }

        placedCrystals.put(position, System.currentTimeMillis());
        countedCrystals.put(position, System.currentTimeMillis());
        placeTimer.reset();
        totalPlaces++;
    }

    private List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();
        if (EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
            players.add(mc.player);
            return players;
        }

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (mc.player.distanceToSqr(player) > Mth.square(enemyRange.getValue().doubleValue())) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;

            players.add(player);
        }

        return players;
    }

    private boolean shouldPause(String process) {
        boolean eatingFlag = (whileEating.getValue().equalsIgnoreCase("None") || (process.equalsIgnoreCase("Attack") && whileEating.getValue().equalsIgnoreCase("Place")) || (process.equalsIgnoreCase("Place") && whileEating.getValue().equalsIgnoreCase("Attack")));
        return eatingFlag && mc.player.isUsingItem();
    }

    private float getMinimumDamage(Player player, float minimumDamage) {
        if (player == null) return minimumDamage;

        if (chestBreak.getValue() && mc.level.getEntities((Entity) null, new AABB(player.blockPosition()).inflate(1), entity -> true).stream().anyMatch(entity -> entity instanceof ItemEntity item && item.getItem().getItem() == Items.OBSIDIAN && item.getItem().getCount() >= 8 && item.tickCount <= 2 + EUClient.SERVER_MANAGER.getPingDelay() + (20 - placeSpeed.getValue().intValue())) && !mc.level.getEntities((Entity) null, new AABB(mc.player.blockPosition()).inflate(1), entity -> true).stream().anyMatch(entity -> entity instanceof ItemEntity item && item.getItem().getItem() == Items.OBSIDIAN && item.getItem().getCount() >= 8 && item.tickCount <= 2 + EUClient.SERVER_MANAGER.getPingDelay() + (20 - placeSpeed.getValue().intValue()))) return 2.0f;
        if (facePlaceMode.getValue().equalsIgnoreCase("None")) return minimumDamage;

        if (facePlaceSpeed.getValue().equalsIgnoreCase("Normal") || facePlaceTimer.hasTimeElapsed(facePlaceDelay.getValue().longValue() * 50L)) {
            if (facePlaceMode.getValue().equalsIgnoreCase("Always")) return Math.min(minimumDamage, 2.0f);
            if (facePlaceMode.getValue().equalsIgnoreCase("Dynamic") && healthPlace.getValue() && (player.getHealth() + player.getAbsorptionAmount()) <= health.getValue().floatValue()) return Math.min(minimumDamage, 2.0f);
            if (facePlaceMode.getValue().equalsIgnoreCase("Dynamic") && armorPlace.getValue()) {
                for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                        net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.LEGS,
                        net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.HEAD}) {
                    ItemStack stack = player.getItemBySlot(slot);
                    if (stack.isEmpty() || stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) == null) continue;
                    if (Math.round(((stack.getMaxDamage() - stack.getDamageValue()) * 100.0) / stack.getMaxDamage()) <= percentage.getValue().intValue()) {
                        return Math.min(minimumDamage, 2.0f);
                    }
                }
            }
        }

        return minimumDamage;
    }

    @Getter @AllArgsConstructor
    public static class PlaceTarget {
        private BlockPos position;
        private Player player;
        private List<Entity> obstructions;
        private BlockPos exception;
        private float damage;
        private int calculations;

        public PlaceTarget clone() {
            return new PlaceTarget(position, player, obstructions, exception, damage, calculations);
        }
    }
}
