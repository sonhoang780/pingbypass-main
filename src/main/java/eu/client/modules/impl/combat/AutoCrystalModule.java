package eu.client.modules.impl.combat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.player.SpeedMineModule;
import eu.client.modules.impl.player.KeyActionModule;
import eu.client.settings.impl.*;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.minecraft.DamageUtils;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.PositionUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.Counter;
import eu.client.utils.system.Timer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import eu.client.utils.graphics.EspShader;

import java.awt.Color;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RegisterModule(name = "AutoCrystal", description = "Automatically places and attacks crystals to annihilate your opponents.", category = Module.Category.COMBAT)
public class AutoCrystalModule extends Module {
    public CategorySetting attackCategory = new CategorySetting("Attack", "The category for settings related to attacking crystals.");
    public BooleanSetting attack = new BooleanSetting("Attack", "Enabled", "Automatically attacks crystals that are deemed safe.", new CategorySetting.Visibility(attackCategory), true);
    public NumberSetting attackSpeed = new NumberSetting("AttackSpeed", "Speed", "The speed at which crystals will be attacked.", new CategorySetting.Visibility(attackCategory), 20.0f, 0.1f, 20.0f);
    public NumberSetting attackRange = new NumberSetting("AttackRange", "Range", "The maximum distance at which crystals will be attacked.", new CategorySetting.Visibility(attackCategory), 4.5, 0.0, 8.0);
    public NumberSetting attackWallsRange = new NumberSetting("AttackWallsRange", "WallsRange", "The maximum distance at which crystals will be attacked through walls.", new CategorySetting.Visibility(attackCategory), 4.5, 0.0, 8.0);
    public ModeSetting antiWeakness = new ModeSetting("AntiWeakness", "Allows you to attack crystals when weaknessed.", new CategorySetting.Visibility(attackCategory), "None", new String[]{"None", "Normal", "Silent"});
    public BooleanSetting instant = new BooleanSetting("Instant", "Instantly attacks crystals once they spawn.", new CategorySetting.Visibility(attackCategory), true);
    public BooleanSetting inhibit = new BooleanSetting("Inhibit", "Prevents excessive attacks on crystals by blacklisting crystals when attacking them.", new CategorySetting.Visibility(attackCategory), true);

    public CategorySetting placeCategory = new CategorySetting("Place", "The category for settings related to placing crystals.");
    public BooleanSetting place = new BooleanSetting("Place", "Enabled", "Automatically places crystals on positions that are deemed safe and lethal enough.", new CategorySetting.Visibility(placeCategory), true);
    public NumberSetting placeSpeed = new NumberSetting("PlaceSpeed", "Speed", "The speed at which crystals will be placed.", new CategorySetting.Visibility(placeCategory), 10.0f, 0.1f, 20.0f);
    public NumberSetting placeRange = new NumberSetting("PlaceRange", "Range", "The maximum distance at which positions will be placed on.", new CategorySetting.Visibility(placeCategory), 4.5, 0.0, 8.0);
    public NumberSetting placeWallsRange = new NumberSetting("PlaceWallsRange", "WallsRange", "The maximum distance at which positions will be placed on through walls.", new CategorySetting.Visibility(placeCategory), 4.5, 0.0, 8.0);
    public ModeSetting placements = new ModeSetting("Placements", "The version of the game that will be used for crystal placement calculations.", new CategorySetting.Visibility(placeCategory), "Native", new String[]{"Native", "Protocol"});
    public BooleanSetting blockDestruction = new BooleanSetting("BlockDestruction", "Places crystals on top of mined blocks in order to damage opponents.", new CategorySetting.Visibility(placeCategory), true);
    public ModeSetting autoSwitch = new ModeSetting("Switch", "Automatically switches to a crystal if you aren't currently holding one.", new CategorySetting.Visibility(placeCategory), "None", new String[]{"None", "Normal", "Silent", "AltSwap"});
    public BooleanSetting swapBack = new BooleanSetting("SwapBack", "Switches back to the item you were holding before the module started switching to crystals, once the module is disabled.", new ModeSetting.Visibility(autoSwitch, "Normal"), false);
    public NumberSetting swapDelay = new NumberSetting("SwapDelay", "Delay", "The delay in ticks after swapping before placing or attacking crystals.", new CategorySetting.Visibility(placeCategory), 0, 0, 20);

    public CategorySetting miscellaneousCategory = new CategorySetting("Miscellaneous", "The category for all miscellaneous settings.");
    public ModeSetting sequential = new ModeSetting("Sequential", "The sequence that the module's processes will be run in.", new CategorySetting.Visibility(miscellaneousCategory), "Strong", new String[]{"None", "Strict", "Strong"});
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the crystal whenever attacking or placing.", new CategorySetting.Visibility(miscellaneousCategory), "Normal", new String[]{"None", "Normal", "Packet", "Silent"});
    public ModeSetting swing = new ModeSetting("Swing", "The hand that will be used for swinging.", new CategorySetting.Visibility(miscellaneousCategory), "Default", new String[]{"Default", "None", "Packet", "Mainhand", "Offhand", "Both"});
    public BooleanSetting yawStep = new BooleanSetting("YawStep", "Performs your rotations over multiple ticks.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public NumberSetting yawStepThreshold = new NumberSetting("YawStepThreshold", "Threshold", "The threshold in order for yaw to be modified.", new BooleanSetting.Visibility(yawStep, true), 75, 1, 180);
    public BooleanSetting raytrace = new BooleanSetting("Raytrace", "Avoids attacking or placing any crystals through walls.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public ModeSetting targetMode = new ModeSetting("Target", "Which player to target when multiple are in range -- narrowing this to one collapses the per-position player scan from O(players) to O(1), the actual fix for multitarget FPS drops.", new CategorySetting.Visibility(miscellaneousCategory), "All", new String[]{"All", "Nearest", "Farthest", "Health"});
    public NumberSetting extrapolation = new NumberSetting("Extrapolation", "Extrapolates the target's position to calculate positions ahead of time.", new CategorySetting.Visibility(miscellaneousCategory), 0, 0, 20);
    public NumberSetting enemyRange = new NumberSetting("EnemyRange", "The maximum distance at which enemies can be at.", new CategorySetting.Visibility(miscellaneousCategory), 10.0, 0.0, 24.0);
    public BooleanSetting chestBreak = new BooleanSetting("ChestBreak", "Prevents other players from getting obsidian from ender chests by destroying the dropped items.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public BooleanSetting mineIgnore = new BooleanSetting("MineIgnore", "Pre-places a crystal on the block SpeedMine is about to break, and detonates it the instant that block is gone.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public NumberSetting mineIgnoreTicks = new NumberSetting("MineIgnoreTicks", "Tick", "How many ticks before the block breaks to place the crystal.", new BooleanSetting.Visibility(mineIgnore, true), 3, 0, 10);
    public BooleanSetting asynchronous = new BooleanSetting("Asynchronous", "Performs calculations on separate threads.", new CategorySetting.Visibility(miscellaneousCategory), true);
    public BooleanSetting pauseOnSecondaryMine = new BooleanSetting("PauseOnSecondaryMine", "Pauses AutoCrystal for one tick while SpeedMine's secondary block is about to break, so it finishes first.", new CategorySetting.Visibility(miscellaneousCategory), true);
    public BooleanSetting gameLoop = new BooleanSetting("GameLoop", "Runs the module on loop instead of ticks.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public NumberSetting loopDelay = new NumberSetting("LoopDelay", "The delay that has to be waited out before running the module again.", new BooleanSetting.Visibility(gameLoop, true), 50, 0, 1000);
    public ModeSetting whileEating = new ModeSetting("WhileEating", "Places and attacks crystal while eating or using items.", new CategorySetting.Visibility(miscellaneousCategory), "Both", new String[]{"None", "Attack", "Place", "Both"});

    public CategorySetting predictionCategory = new CategorySetting("Prediction", "The category for settings related to attack prediction.");
    public BooleanSetting godSync = new BooleanSetting("GodSync", "Makes the attacking way faster by predicting entity IDs.", new CategorySetting.Visibility(predictionCategory), false);
    public NumberSetting predictions = new NumberSetting("Predictions", "The amount of predictions that will be done after placing.", new CategorySetting.Visibility(predictionCategory), 10, 1, 20);
    public NumberSetting offset = new NumberSetting("Offset", "The amount that the last entity ID should be offset by.", new CategorySetting.Visibility(predictionCategory), 0, 0, 2);
    public ModeSetting godSwing = new ModeSetting("GodSwing", "Swing", "The swinging that will be done for each predicted attack.", new CategorySetting.Visibility(predictionCategory), "Normal", new String[]{"None", "Normal", "Strict"});
    public BooleanSetting fast = new BooleanSetting("Fast", "Improves the speed of the prediction calculations at the cost of stability.", new CategorySetting.Visibility(predictionCategory), false);
    public BooleanSetting antiKick = new BooleanSetting("AntiKick", "Prevents you from getting kicked by attacking invalid entity IDs.", new CategorySetting.Visibility(predictionCategory), false);
    public NumberSetting kickThreshold = new NumberSetting("KickThreshold", "Threshold", "The tick threshold for the kick prevention.", new BooleanSetting.Visibility(antiKick, true), 5, 1, 10);

    public CategorySetting facePlaceCategory = new CategorySetting("Faceplace", "The category for settings relating to faceplacing.");
    public ModeSetting facePlaceMode = new ModeSetting("FaceplaceMode", "Mode", "The checks that will be done in order to faceplace.", new CategorySetting.Visibility(facePlaceCategory), "Dynamic", new String[]{"None", "Dynamic", "Always"});
    public ModeSetting facePlaceSpeed = new ModeSetting("FaceplaceSpeed", "Speed", "The speed that players will be faceplaced at.", new CategorySetting.Visibility(facePlaceCategory), "Normal", new String[]{"Normal", "Custom"});
    public NumberSetting facePlaceDelay = new NumberSetting("FaceplaceDelay", "Delay", "The ticks that have to be waited for before faceplacing again.", new ModeSetting.Visibility(facePlaceSpeed, "Custom"), 11, 0, 20);
    public BooleanSetting healthPlace = new BooleanSetting("HealthPlace", "Whether or not to faceplace when the target's health is low.", new ModeSetting.Visibility(facePlaceMode, "Dynamic"), true);
    public NumberSetting health = new NumberSetting("Health", "The health that the target needs to be at in order for the module to start faceplacing.", new BooleanSetting.Visibility(healthPlace, true), 8.0f, 0.0f, 36.0f);
    public BooleanSetting armorPlace = new BooleanSetting("ArmorPlace", "Whether or not to faceplace when the target's armor is low on durability.", new ModeSetting.Visibility(facePlaceMode, "Dynamic"), true);
    public NumberSetting percentage = new NumberSetting("Percentage", "The percentage that one of the target's armor pieces need to be at in order to start faceplacing.", new BooleanSetting.Visibility(armorPlace, true), 10, 1, 100);

    public CategorySetting basePlaceCategory = new CategorySetting("BasePlace", "The category for settings related to placing base obsidian blocks for elevated crystal attacks.");
    public BooleanSetting basePlace = new BooleanSetting("BasePlace", "Enabled", "Automatically places obsidian base blocks in a line under/next to elevated targets.", new CategorySetting.Visibility(basePlaceCategory), true);
    public ModeSetting basePlaceSwitch = new ModeSetting("BaseSwitch", "Switch", "Switch mode for placing base blocks.", new CategorySetting.Visibility(basePlaceCategory), "Silent", new String[]{"None", "Normal", "Silent", "AltSwap"});
    public NumberSetting basePlaceRange = new NumberSetting("BaseRange", "Range", "The maximum distance at which base blocks will be placed.", new CategorySetting.Visibility(basePlaceCategory), 4.5, 0.0, 6.0);
    public NumberSetting basePlaceDelay = new NumberSetting("BaseDelay", "Delay", "Delay in ticks between placing base blocks.", new CategorySetting.Visibility(basePlaceCategory), 0, 0, 10);
    public BooleanSetting basePlaceRotate = new BooleanSetting("BaseRotate", "Rotate", "Rotates when placing base blocks.", new CategorySetting.Visibility(basePlaceCategory), false);

    public CategorySetting damageCategory = new CategorySetting("Damage", "The category for settings related to damage calculations.");
    public NumberSetting minimumDamage = new NumberSetting("MinimumDamage", "Minimum", "The minimum damage that has to be dealt to enemies.", new CategorySetting.Visibility(damageCategory), 6.0, 0.0, 36.0);
    public NumberSetting maximumSelfDamage = new NumberSetting("MaximumSelfDamage", "MaximumSelf", "The maximum damage that can be dealt to you by crystals.", new CategorySetting.Visibility(damageCategory), 10.0, 0.0, 36.0);
    public NumberSetting lethalMultiplier = new NumberSetting("LethalMultiplier", "The amount of crystals that the target has to be killed by in order to ignore minimum damage.", new CategorySetting.Visibility(damageCategory), 1.5f, 0.0f, 4.0f);
    public BooleanSetting antiSuicide = new BooleanSetting("AntiSuicide", "Prevents crystals from accidentally killing you when you're low on health.", new CategorySetting.Visibility(damageCategory), true);
    public BooleanSetting ignoreTerrain = new BooleanSetting("IgnoreTerrain", "Ignores terrain that can be destroyed when calculating damage.", new CategorySetting.Visibility(damageCategory), true);

    public CategorySetting renderCategory = new CategorySetting("Render", "Contains all of the settings relating to position rendering.");
    public ModeSetting animationMode = new ModeSetting("Animation", "The animation that will be applied to the rendering.", new CategorySetting.Visibility(renderCategory), "Static", new String[]{"Static", "Slide"});
    public ModeSetting mode = new ModeSetting("Mode", "The mode for the auto crystal render.", new CategorySetting.Visibility(renderCategory), "Fade", new String[]{"Fade", "Shrink"});
    public NumberSetting duration = new NumberSetting("Duration", "The duration for the place render.", new CategorySetting.Visibility(renderCategory), 300, 0, 1000);
    public NumberSetting slideSmoothness = new NumberSetting("Smoothness", "The smoothness for the slide while place position is changing.", new CategorySetting.Visibility(renderCategory), 1, 0, 20);
    public ModeSetting renderMode = new ModeSetting("RenderMode", "The rendering that will be applied to the target position.", new CategorySetting.Visibility(renderCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColorUp = new ColorSetting("FillColorUp", "The color that will be used for the fill gradiant upper part rendering.", new ModeSetting.Visibility(renderMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting fillColorDown = new ColorSetting("FillColorDown", "The color that will be used for the fill gradiant lower part rendering.", new ModeSetting.Visibility(renderMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColorUp = new ColorSetting("OutlineColorUp", "The color that will be used for the outline gradiant upper part rendering.", new ModeSetting.Visibility(renderMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public ColorSetting outlineColorDown = new ColorSetting("OutlineColorDown", "The color that will be used for the outline gradiant lower part rendering.", new ModeSetting.Visibility(renderMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    public BooleanSetting renderDamage = new BooleanSetting("RenderDamage", "Damage", "Renders the damage that the position will do to the opponent.", new CategorySetting.Visibility(renderCategory), false);
    public BooleanSetting icon = new BooleanSetting("Icon", "Renders a customizable crystal icon on the rendered position.", new CategorySetting.Visibility(renderCategory), false);
    public NumberSetting iconScale = new NumberSetting("IconScale", "The scaling that will be applied to the crystal icon rendering.", new BooleanSetting.Visibility(icon, true), 3, 1, 5);
    public NumberSetting iconRadius = new NumberSetting("IconRadius", "The difference between the outer circle and the inner circle.", new BooleanSetting.Visibility(icon, true), 2.0f, 0.0f, 5.0f);
    public ColorSetting iconColor = new ColorSetting("IconColor", "The color that will be used for the crystal icon rendering.", new BooleanSetting.Visibility(icon, true), ColorUtils.getDefaultColor());

    private static final String[] SHADER_MODES = EspShader.MODES;
    private static final String[] SHADER_ACTIVE_MODES = java.util.Arrays.copyOfRange(SHADER_MODES, 1, SHADER_MODES.length);

    public ModeSetting shader = new ModeSetting("Shader", "The animated shader that will be drawn on the target position instead of a flat color.", new CategorySetting.Visibility(renderCategory), "None", SHADER_MODES);
    public NumberSetting shaderSpeed = new NumberSetting("ShaderSpeed", "Speed", "The speed at which the shader animates.", new ModeSetting.Visibility(shader, SHADER_ACTIVE_MODES), 1.0f, 0.1f, 10.0f);
    public NumberSetting shaderOpacity = new NumberSetting("ShaderOpacity", "Opacity", "The opacity of the shader rendering.", new ModeSetting.Visibility(shader, SHADER_ACTIVE_MODES), 100, 0, 100);
    public BooleanSetting shaderDistanceScaling = new BooleanSetting("ShaderDistanceScaling", "DistanceScaling", "Scales the shader pattern by your distance to the position.", new ModeSetting.Visibility(shader, SHADER_ACTIVE_MODES), false);
    public NumberSetting shaderStep = new NumberSetting("ShaderStep", "Step", "The size of the gradient bands.", new ModeSetting.Visibility(shader, "Gradient"), 50.0f, 0.1f, 200.0f);
    public ColorSetting shaderColor1 = new ColorSetting("ShaderColor1", "The first gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(255, 0, 255, 255), false, false));
    public ColorSetting shaderColor2 = new ColorSetting("ShaderColor2", "The second gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(255, 0, 0, 255), false, false));
    public ColorSetting shaderColor3 = new ColorSetting("ShaderColor3", "The third gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(0, 255, 0, 255), false, false));
    public ColorSetting shaderColor4 = new ColorSetting("ShaderColor4", "The fourth gradient color.", new ModeSetting.Visibility(shader, "Gradient"), new ColorSetting.Color(new Color(0, 0, 255, 255), false, false));
    public ColorSetting shaderGlowColor = new ColorSetting("ShaderGlowColor", "GlowColor", "The color that the glow shader will be tinted with.", new ModeSetting.Visibility(shader, "Glowing"), new ColorSetting.Color(new Color(255, 0, 255, 255), false, false));

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile java.util.concurrent.Future<?> pendingCalc = null;
    private volatile boolean asyncLoopActive = false;
    private volatile Set<BlockPos> latestReservedPlacements = Set.of();

    private Runnable attackRunnable = null;
    private Runnable placeRunnable = null;

    private final Map<Integer, Long> attackedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> placedCrystals = new ConcurrentHashMap<>();
    // Positions the server confirmed-rejected (placedCrystals' own TTL expired with no live crystal
    // ever seen there) -- kept out of calculatePlacements briefly so it falls back to the next-best
    // candidate instead of re-picking the same dead spot every cycle forever. Short-lived on purpose:
    // this is "the server said no just now", not a permanent judgement about the block.
    private final Map<BlockPos, Long> placeBlacklist = new ConcurrentHashMap<>();
    private static final long PLACE_BLACKLIST_MS = 1500L;
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
    private PlaceTarget mineTargetSecondary = null;

    private BlockPos mineIgnoreMinedPos = null;
    private BlockPos mineIgnorePlacedPos = null;

    private String calculationTime = "0.00ms";
    private int calculationCount = 0;
    @Getter private String calculationDamage = "0.00";

    private final Counter crystalCounter = new Counter();
    private int crystalsPerSecond = 0;

    private int highestID = -100000;
    private int kickTicks = 0;


    private List<Player> cachedPlayers = new ArrayList<>();
    private long lastPlayerCacheTime = 0L;
    private static final long PLAYER_CACHE_DURATION = 50L;

    private int basePlaceTicks = 0;
    private int savedSlot = -1;

    private boolean isDead() {
        if (mc.player == null || mc.level == null) return true;
        if (!mc.player.isAlive() || mc.player.isDeadOrDying() || mc.player.getHealth() <= 0.0f) return true;
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) return true;
        return false;
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead()) {
            attackRunnable = null;
            placeRunnable = null;
            target = null;
            placeTarget = null;
            attackTarget = null;
            mineTarget = null;
            placedCrystals.clear();
            placeBlacklist.clear();
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
        placedCrystals.entrySet().removeIf(entry -> {
            if (System.currentTimeMillis() - entry.getValue() <= placedTtl) return false;

            boolean stillLive = mc.level.getEntities((Entity) null, new AABB(entry.getKey().above()), e -> e instanceof EndCrystal)
                    .stream().anyMatch(Entity::isAlive);
            if (stillLive) {
                placedCrystals.put(entry.getKey(), System.currentTimeMillis());
                return false;
            }

            // Confirmed rejection: we sent the place packet, waited a full ack window (ping-scaled),
            // and the server never actually spawned a crystal here. Obsidian's still there and
            // nothing else about the candidate looks invalid, so without this it just wins
            // calculatePlacements again next cycle -- reported as "AutoCrystal keeps trying a spot
            // the server won't allow, best-damage cell manual-placing fine elsewhere".
            placeBlacklist.put(entry.getKey(), System.currentTimeMillis());
            return true;
        });
        placeBlacklist.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > PLACE_BLACKLIST_MS);
        countedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, minTtl));

        crystalsPerSecond = crystalCounter.getCount();

        if (gameLoop.getValue()) return;

        run();
    }

    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead()) return;

        mineIgnoreTick();

        latestReservedPlacements = Set.copyOf(EUClient.WORLD_MANAGER.getReservedPlacements());

        if (asynchronous.getValue()) {
            if (!asyncLoopActive) {
                asyncLoopActive = true;
                pendingCalc = executor.submit(this::asyncLoopStep);
            }
        } else {
            asyncLoopActive = false;
            runCalculation(latestReservedPlacements, false);
        }
    }

    private void asyncLoopStep() {
        if (!asyncLoopActive || !asynchronous.getValue()) {
            asyncLoopActive = false;
            return;
        }

        runCalculation(latestReservedPlacements, true);

        if (asyncLoopActive && asynchronous.getValue()) {
            pendingCalc = executor.submit(this::asyncLoopStep);
        } else {
            asyncLoopActive = false;
        }
    }

    private void runCalculation(Set<BlockPos> reservedPlacements, boolean async) {
        long startTime = System.nanoTime();

        attackTarget = calculateCrystals();
        placeTarget = calculatePlacements(null, reservedPlacements);

        long calcNanos = System.nanoTime() - startTime;
        calculationTime = new DecimalFormat("0.00").format(calcNanos / 1000000.0) + "ms";
        calculationCount = placeTarget == null ? 0 : placeTarget.getCalculations();
        calculationDamage = placeTarget == null ? "0.00" : new DecimalFormat("0.00").format(placeTarget.getDamage());

        target = placeTarget == null ? null : placeTarget.getPlayer();

        if (blockDestruction.getValue() && async) {
            SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
            BlockPos position = null;

            if (module.getPrimary() != null && module.getPrimary().isMining()) position = module.getPrimary().getPosition();
            if (position != null) mineTarget = calculatePlacements(position, reservedPlacements);

            BlockPos secondaryPosition = null;
            if (module.farReach.getValue()) {
                if (module.getSecondary() != null && module.getSecondary().isMining()) secondaryPosition = module.getSecondary().getPosition();
            } else {
                if (module.getLegacySecondary() != null && module.getLegacySecondary().isMining()) secondaryPosition = module.getLegacySecondary().getPosition();
            }
            if (secondaryPosition != null) mineTargetSecondary = calculatePlacements(secondaryPosition, reservedPlacements);
        }
    }

    @SubscribeEvent
    public void onGameLoop(GameLoopEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead()) return;
        if (!gameLoop.getValue()) return;
        if (!loopTimer.hasTimeElapsed(loopDelay.getValue().longValue()))
            return;

        loopTimer.reset();
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
        if (pauseForSecondaryMine()) return;

        attackRunnable = null;
        placeRunnable = null;

        if (basePlace.getValue()) {
            if (basePlaceTicks < basePlaceDelay.getValue().intValue()) {
                basePlaceTicks++;
            } else {
                if (executeBasePlace()) {
                    basePlaceTicks = 0;
                }
            }
        }

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


    private static final float SECONDARY_PAUSE_PROGRESS = 0.85f;

    private boolean pauseForSecondaryMine() {
        if (!pauseOnSecondaryMine.getValue()) return false;

        SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
        if (module == null) return false;

        if (module.farReach.getValue()) {
            SpeedMineModule.Secondary secondary = module.getSecondary();
            return secondary != null && secondary.isMining() && secondary.getProgress() >= SECONDARY_PAUSE_PROGRESS;
        } else {
            SpeedMineModule.Action legacySecondary = module.getLegacySecondary();
            return legacySecondary != null && legacySecondary.isMining() && legacySecondary.getProgress() >= SECONDARY_PAUSE_PROGRESS;
        }
    }

    @SubscribeEvent
    public void onUpdateMovement$POST(UpdateMovementEvent.Post event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead()) return;

        if (attackRunnable != null) attackRunnable.run();
        if (placeRunnable != null) placeRunnable.run();
    }

    @SubscribeEvent
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead() || getPlayers().isEmpty()) return;
        if (shouldPause("Attack")) return;

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

        float[] entityAttackRotations = RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition()));
        if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), entityAttackRotations);
        if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(calculateRotations(Vec3.atCenterOf(crystal.blockPosition())), EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));

        attack(crystal);

        attackedSequentially = true;
        if (sequential.getValue().equalsIgnoreCase("Strong")) {
            placeCrystals(true);
        }
    }

    @SubscribeEvent
    public void onDestroyBlock(DestroyBlockEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (isDead() || getPlayers().isEmpty()) return;
        if (shouldPause("Place")) return;
        kickTicks = 0;

        if (mineIgnore.getValue() && event.getPosition() != null && event.getPosition().equals(mineIgnoreMinedPos)) mineIgnoreDetonate();

        if (!blockDestruction.getValue()) return;
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;

        BlockPos minedPosition = event.getPosition();
        if (minedPosition == null) return;

        int slot = InventoryUtils.findHotbar(Items.END_CRYSTAL);
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean switched = false;

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1 && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL))
            return;

        PlaceTarget primaryCandidate = this.mineTarget == null ? null : this.mineTarget.clone();
        PlaceTarget secondaryCandidate = this.mineTargetSecondary == null ? null : this.mineTargetSecondary.clone();

        PlaceTarget mineTarget;
        if (primaryCandidate != null && primaryCandidate.getPosition() != null && minedPosition.equals(primaryCandidate.getException())) {
            mineTarget = primaryCandidate;
        } else if (secondaryCandidate != null && secondaryCandidate.getPosition() != null && minedPosition.equals(secondaryCandidate.getException())) {
            mineTarget = secondaryCandidate;
        } else if (!asynchronous.getValue()) {
            mineTarget = calculatePlacements(minedPosition);
        } else {
            mineTarget = primaryCandidate != null ? primaryCandidate : secondaryCandidate;
        }
        if (mineTarget == null || mineTarget.getPosition() == null) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        BlockPos position = mineTarget.getPosition();

        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        EUClient.RENDER_MANAGER.setRenderPosition(position);

        if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue())))
            return;

        // A crystal already sitting where we're about to place (leftover from a previous
        // placement) has to be attacked THIS tick, not aimed-at-then-placed-over. Previously this
        // rotated toward placeRotations (the placement point) AND, if a crystal existed, rotated
        // AGAIN toward the crystal right after -- two real rotation packets (Silent/Packet) at two
        // different targets back-to-back, then an attack fired off the second one with no throttle
        // of its own (bypassed attack()'s attackTimer/attackedCrystals dedup entirely, raw
        // ServerboundAttackPacket+Swing send). That double-rotate-then-unthrottled-attack pattern
        // is what nami never does -- doPlace/doBreak are separate ticks gated by their own
        // placeTimer/breakTimer, and doBreak only ever submits one rotation, at the crystal.
        //
        // Now: attack existing crystal -> rotate once at IT, route through attack() (shares the
        // same throttle/dedup as every other attack call site), and skip the placement point
        // entirely this tick. Only rotate toward the placement point when there is nothing to
        // attack there, since we're actually about to place() at that rotation below.
        EndCrystal existingCrystal = null;
        for (Entity entity : mc.level.getEntities((Entity) null, new AABB(position.above()), entity -> true)) {
            if (entity instanceof EndCrystal crystal) {
                existingCrystal = crystal;
                break;
            }
        }

        if (existingCrystal != null) {
            float[] entityRotations = RotationUtils.getRotations(existingCrystal);
            if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(entityRotations, this, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), entityRotations);

            attack(existingCrystal);
        } else {
            float[] placeRotations = calculateRotations(Vec3.atCenterOf(position).add(0, 1, 0));
            if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(placeRotations, this, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this) + 1);
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), placeRotations);
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
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
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
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket) {
            swapTimer.reset();
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        kickTicks = 0;
        attackRunnable = null;
        placeRunnable = null;
        target = null;
        placeTarget = null;
        attackTarget = null;
        mineTarget = null;
        placedCrystals.clear();
        placeBlacklist.clear();
        attackedCrystals.clear();
        countedCrystals.clear();
        EUClient.RENDER_MANAGER.setRenderPosition(null);
    }

    @SubscribeEvent
    public void onClientConnect(ClientConnectEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        highestID = -100000;
    }

    @Override
    public void onDisable() {
        asyncLoopActive = false;
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

        cachedPlayers.clear();
        lastPlayerCacheTime = 0L;
    }

    @Override
    public String getMetaData() {
        return calculationTime + ", " + calculationCount + ", " + calculationDamage + ", " + crystalsPerSecond;
    }

    private void attackCrystals() {
        if (isDead()) return;
        if (shouldPause("Attack")) return;
        if (getPlayers().isEmpty()) {
            attackTarget = null;
            return;
        }

        EndCrystal overrideCrystal = null;

        PlaceTarget pt = this.placeTarget;
        boolean flag = pt != null && pt.getPosition() == null && pt.obstructions != null && !pt.obstructions.isEmpty();
        for (Entity entity : flag ? pt.obstructions : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) continue;
            if (!crystal.isAlive()) continue;
            if (inhibit.getValue() && attackedCrystals.containsKey(entity.getId())) continue;
            boolean isKnownObstruction = !flag && pt != null && pt.obstructions != null && pt.obstructions.contains(crystal);
            if (!flag && !isKnownObstruction && !placedCrystals.containsKey(crystal.blockPosition().below())) continue;
            if (crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(crystal.blockPosition())) continue;
            if (!WorldUtils.canSee(crystal) && (raytrace.getValue() || crystal.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > Mth.square(attackWallsRange.getValue().doubleValue())))
                continue;

            overrideCrystal = crystal;
            break;
        }

        EndCrystal crystal = overrideCrystal == null ? attackTarget : overrideCrystal;
        if (crystal == null) return;

        float[] attackTargetRotations = calculateRotations(Vec3.atCenterOf(crystal.blockPosition()));
        if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(attackTargetRotations, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));

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
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));

            attack(crystal);
        };
    }

    private void placeCrystals(boolean sequential) {
        if (isDead()) return;
        if (shouldPause("Place")) return;
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

        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        EUClient.RENDER_MANAGER.setRenderPosition(position);

        if (!mc.level.getWorldBorder().isWithinBounds(position)) return;
        if (mc.level.getBlockState(position).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(position).getBlock() != Blocks.BEDROCK) return;
        if (!mc.level.getBlockState(position.offset(0, 1, 0)).isAir() || (placements.getValue().equalsIgnoreCase("Protocol") && !mc.level.getBlockState(position.offset(0, 2, 0)).isAir())) return;
        if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue()))) return;
        if (mc.level.getEntities((Entity) null, getCrystalPlacementBox(position), entity -> true).stream().anyMatch(entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal))) return;

        float[] placeTargetRotations = calculateRotations(Vec3.atCenterOf(position).add(0, 1, 0));
        if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(placeTargetRotations, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));

        if (swapDelay.getValue().intValue() > 0 && !swapTimer.hasTimeElapsed(swapDelay.getValue().longValue() * 50L)) return;
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;
        if (!sequential && placedSequentially) {
            placedSequentially = false;
            return;
        }

        placeRunnable = () -> {
            boolean switched = false;

            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(position).add(0, 1, 0)));

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

        List<Player> players = selectTargets(getPlayers());
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

    private PlaceTarget calculatePlacements(BlockPos exception) {
        return calculatePlacements(exception, EUClient.WORLD_MANAGER.getReservedPlacements());
    }

    private PlaceTarget calculatePlacements(BlockPos exception, Set<BlockPos> reservedPlacements) {
        if (!place.getValue()) return null;

        if (shouldPause("Place") || ((autoSwitch.getValue().equalsIgnoreCase("None") || InventoryUtils.findHotbar(Items.END_CRYSTAL) == -1) && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL)))
            return null;

        List<Player> players = selectTargets(getPlayers());
        if (players.isEmpty()) return null;

        BlockPos bestPosition = null;
        Player bestPlayer = null;
        float bestDamage = 0.0f;

        BlockPos stickyPos = this.placeTarget == null ? null : this.placeTarget.getPosition();
        Player stickyPlayer = null;
        float stickyDamage = 0.0f;

        List<Entity> obstructions = new ArrayList<>();
        int calculations = 0;

        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(Math.max(placeRange.getValue().doubleValue(), placeWallsRange.getValue().doubleValue())); i++) {
            BlockPos position = mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i));

            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(position)) continue;
            if (mc.level.getBlockState(position).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(position).getBlock() != Blocks.BEDROCK) continue;
            if (placeBlacklist.containsKey(position)) continue;
            if (!mc.level.getBlockState(position.offset(0, 1, 0)).isAir() || (placements.getValue().equalsIgnoreCase("Protocol") && !mc.level.getBlockState(position.offset(0, 2, 0)).isAir())) continue;

            if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue()))) continue;

            AABB crystalBox = new AABB(position.getX() - 1, position.getY() + 1, position.getZ() - 1, position.getX() + 2, position.getY() + 3, position.getZ() + 2);
            if (reservedPlacements.stream().anyMatch(reserved -> crystalBox.intersects(new AABB(reserved)))) continue;

            List<Entity> entitiesAtPos = mc.level.getEntities((Entity) null, getCrystalPlacementBox(position), entity -> entity.isAlive() && !(entity instanceof ExperienceOrb));
            boolean hasNonCrystalEntity = false;
            List<Entity> obstructingCrystals = new ArrayList<>();
            for (Entity entity : entitiesAtPos) {
                if (entity instanceof EndCrystal crystal) {
                    if (!placedCrystals.containsKey(crystal.blockPosition().below()) || crystal.tickCount >= (20 - attackSpeed.getValue().intValue()) + 15) {
                        obstructingCrystals.add(crystal);
                    }
                } else {
                    hasNonCrystalEntity = true;
                    break;
                }
            }
            if (hasNonCrystalEntity) continue;

            // Early exit: Crystal explosion max damage reach is 12.0 blocks. Skip damage raycasts if out of range of all targets.
            double maxDistSq = Mth.square(12.0);
            boolean inRange = false;
            for (Player player : players) {
                if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 1.0, position.getZ() + 0.5) <= maxDistSq) {
                    inRange = true;
                    break;
                }
            }
            if (!inRange) continue;

            if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
                float selfDamage = DamageUtils.getCrystalDamage(mc.player, null, position, exception, ignoreTerrain.getValue());
                if (selfDamage > maximumSelfDamage.getValue().floatValue()) continue;
                if (antiSuicide.getValue() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
            }

            for (Player player : players) {
                calculations++;

                float damage = DamageUtils.getCrystalDamage(player, PositionUtils.extrapolate(player, extrapolation.getValue().intValue()), position, exception, ignoreTerrain.getValue());
                if (damage < getMinimumDamage(player, minimumDamage.getValue().floatValue()) && damage < player.getHealth() + player.getAbsorptionAmount() && !(damage * (1.0f + lethalMultiplier.getValue().floatValue()) >= player.getHealth() + player.getAbsorptionAmount()))
                    continue;

                if (exception == null && !obstructingCrystals.isEmpty()) {
                    obstructions.add(obstructingCrystals.getFirst());
                    break;
                }

                if (damage > bestDamage) {
                    bestPosition = position;
                    bestPlayer = player;
                    bestDamage = damage;
                }

                if (stickyPos != null && position.equals(stickyPos)) {
                    if (damage > stickyDamage) {
                        stickyPlayer = player;
                        stickyDamage = damage;
                    }
                }
            }
        }

        if (bestPosition == null) {
            return new PlaceTarget(null, null, obstructions, null, 0.0f, calculations);
        }

        BlockPos finalPosition = bestPosition;
        Player finalPlayer = bestPlayer;
        float finalDamage = bestDamage;

        boolean stickyStillValid = stickyPos != null && stickyDamage > 0.0f && stickyPlayer != null 
                && stickyPlayer.distanceToSqr(Vec3.atCenterOf(stickyPos)) <= Mth.square(2.2);

        if (stickyStillValid && !bestPosition.equals(stickyPos)) {
            if (stickyDamage < 4.0f && bestDamage < 4.0f) {
                if (Math.abs(bestDamage - stickyDamage) <= 0.2f) {
                    double stickyDist = stickyPlayer.distanceToSqr(Vec3.atCenterOf(stickyPos));
                    double bestDist = bestPlayer.distanceToSqr(Vec3.atCenterOf(bestPosition));
                    if (stickyDist <= bestDist) {
                        finalPosition = stickyPos;
                        finalPlayer = stickyPlayer;
                        finalDamage = stickyDamage;
                    }
                } else if (bestDamage <= stickyDamage + 0.2f) {
                    finalPosition = stickyPos;
                    finalPlayer = stickyPlayer;
                    finalDamage = stickyDamage;
                }
            } else {
                boolean isSignificantlyBetter = (bestDamage > stickyDamage * 1.05f) && (bestDamage >= stickyDamage + 0.8f);
                if (!isSignificantlyBetter) {
                    finalPosition = stickyPos;
                    finalPlayer = stickyPlayer;
                    finalDamage = stickyDamage;
                }
            }
        }

        return new PlaceTarget(finalPosition, finalPlayer, obstructions, exception, finalDamage, calculations);
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

        if (switchedSlot != -1) {
            InventoryUtils.switchBack(antiWeakness.getValue(), switchedSlot, previousSlot);
        }

        attackedCrystals.put(crystal.getId(), System.currentTimeMillis());
        attackTimer.reset();
        totalAttacks++;
    }

    private void place(BlockPos position) {
        InteractionHand hand = mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        
        CrystalPlacementHelper.PlacementResult placement = CrystalPlacementHelper.getVisiblePlacement(position);

        eu.client.utils.minecraft.NetworkUtils.sendSequencedPacket(sequence ->
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

    private void mineIgnoreTick() {
        if (!mineIgnore.getValue()) {
            mineIgnoreMinedPos = null;
            mineIgnorePlacedPos = null;
            return;
        }

        SpeedMineModule.Action primary = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class).getPrimary();
        if (primary == null || !primary.isMining()) {
            mineIgnoreMinedPos = null;
            mineIgnorePlacedPos = null;
            return;
        }

        BlockPos minePos = primary.getPosition();
        if (!minePos.equals(mineIgnoreMinedPos)) {
            mineIgnoreMinedPos = minePos;
            mineIgnorePlacedPos = null;
        }

        if (mineIgnorePlacedPos != null) return;
        if (primary.getTicksRemaining() > mineIgnoreTicks.getValue().intValue()) return;

        mineIgnoreTryPlace(minePos);
    }

    private void mineIgnoreTryPlace(BlockPos minePos) {
        if (shouldPause("Place")) return;
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(minePos.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) candidates.add(minePos.relative(direction).above());

        int slot = InventoryUtils.findHotbar(Items.END_CRYSTAL);
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1
                && mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL)
            return;

        for (BlockPos candidate : candidates) {
            BlockPos support = candidate.below();
            if (mc.level.getBlockState(support).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(support).getBlock() != Blocks.BEDROCK) continue;
            if (!mc.level.getBlockState(candidate).isAir()) continue;
            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate)) > Mth.square(placeRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(candidate)) continue;
            if (!WorldUtils.canSeeBlock(support) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate)) > Mth.square(placeWallsRange.getValue().doubleValue()))) continue;
            if (mc.level.getEntities((Entity) null, new AABB(candidate), entity -> true).stream().anyMatch(entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal))) continue;

            AABB crystalBox = new AABB(candidate.getX() - 1, candidate.getY(), candidate.getZ() - 1, candidate.getX() + 2, candidate.getY() + 2, candidate.getZ() + 2);
            if (EUClient.WORLD_MANAGER.getReservedPlacements().stream().anyMatch(reserved -> crystalBox.intersects(new AABB(reserved)))) continue;

            if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
                float selfDamage = DamageUtils.getCrystalDamage(mc.player, null, candidate, null, ignoreTerrain.getValue());
                if (selfDamage > maximumSelfDamage.getValue().floatValue()) continue;
                if (antiSuicide.getValue() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
            }

            if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(calculateRotations(Vec3.atCenterOf(candidate)), EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(candidate)));

            boolean switched = false;
            if (mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
                if (autoSwitch.getValue().equalsIgnoreCase("Normal") && swapBack.getValue() && savedSlot == -1) savedSlot = previousSlot;
                InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);
                switched = true;
            }

            place(candidate);

            if (switched) InventoryUtils.switchBack(autoSwitch.getValue(), slot, previousSlot);

            mineIgnorePlacedPos = candidate;
            return;
        }
    }

    private void mineIgnoreDetonate() {
        if (shouldPause("Attack")) return;
        BlockPos placedPos = mineIgnorePlacedPos;
        mineIgnoreMinedPos = null;
        mineIgnorePlacedPos = null;
        if (placedPos == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) continue;
            if (!crystal.blockPosition().below().equals(placedPos)) continue;

            if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(calculateRotations(Vec3.atCenterOf(crystal.blockPosition())), EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));

            attack(crystal);
            return;
        }
    }

    // Was: unconditionally `if (EntityUtils.isGhost(player)) continue;` -- excluded every LogoutSpot
    // ghost outright instead of targeting them, unlike SpeedMineModule#getTarget/AutoTrapModule
    // #getTarget's proven pattern (add ghosts as extra candidates, exempt only THEM from the
    // isAlive() check since a logged-out ghost is inherently "dead" but still a valid target).
    // Reported: AutoCrystal only working against FakePlayer, dating from when LogoutSpot was added
    // -- this method is the one place in the three that never got the same treatment. Aligned to
    // the identical working pattern rather than guessing at what else differed.
    private List<Player> getPlayers() {
        if (EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
            return List.of(mc.player);
        }

        long now = System.currentTimeMillis();
        if (now - lastPlayerCacheTime < PLAYER_CACHE_DURATION) return cachedPlayers;

        eu.client.modules.impl.visuals.PopChamsModule popChams = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class) : null;
        eu.client.modules.impl.visuals.LogoutSpotModule logoutSpot = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.LogoutSpotModule.class) : null;

        List<Player> allCandidates = new ArrayList<>(mc.level.players());
        if (logoutSpot != null && logoutSpot.isToggled()) {
            for (Player ghost : logoutSpot.getGhosts()) {
                if (ghost != null && !allCandidates.contains(ghost)) {
                    allCandidates.add(ghost);
                }
            }
        }

        List<Player> players = new ArrayList<>();
        for (Player player : allCandidates) {
            if (player == mc.player) continue;
            if (popChams != null && popChams.isGhost(player)) continue; // Ignore PopChams ghosts
            if (logoutSpot == null || !logoutSpot.isGhost(player)) {
                if (!player.isAlive()) continue;
            }
            if (mc.player.distanceToSqr(player) > Mth.square(enemyRange.getValue().doubleValue())) continue;
            if (logoutSpot != null && logoutSpot.isGhost(player)) {
                eu.client.modules.impl.visuals.LogoutSpotModule.Spot spot = logoutSpot.getSpot((net.minecraft.client.player.RemotePlayer) player);
                if (spot != null && EUClient.FRIEND_MANAGER.contains(spot.data.name)) continue;
            } else {
                if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
            }

            players.add(player);
        }

        cachedPlayers = players;
        lastPlayerCacheTime = now;
        return players;
    }

    private List<Player> selectTargets(List<Player> players) {
        if (players.size() <= 1 || targetMode.getValue().equalsIgnoreCase("All")) return players;

        Player best = null;
        double bestScore = 0.0;

        for (Player player : players) {
            double score = switch (targetMode.getValue()) {
                case "Nearest" -> -mc.player.distanceToSqr(player);
                case "Farthest" -> mc.player.distanceToSqr(player);
                default -> -(player.getHealth() + player.getAbsorptionAmount());
            };

            if (best == null || score > bestScore) {
                best = player;
                bestScore = score;
            }
        }

        return List.of(best);
    }

    private boolean shouldPause(String process) {
        KeyActionModule keyAction = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(KeyActionModule.class) : null;
        if (keyAction != null && keyAction.isPearlActive()) return true;

        boolean eatingFlag = (whileEating.getValue().equalsIgnoreCase("None") || (process.equalsIgnoreCase("Attack") && whileEating.getValue().equalsIgnoreCase("Place")) || (process.equalsIgnoreCase("Place") && whileEating.getValue().equalsIgnoreCase("Attack")));
        return eatingFlag && eu.client.utils.minecraft.EntityUtils.isEating();
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

    private boolean executeBasePlace() {
    if (isDead()) return false;
    if (!basePlace.getValue()) return false;
    if (shouldPause("Place")) return false;

    Player targetPlayer = this.target;
    if (targetPlayer == null) {
        double bestDistSq = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (EntityUtils.isGhost(player)) continue;
            if (player == mc.player || !player.isAlive()) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
            double distSq = mc.player.distanceToSqr(player);
            if (distSq <= Mth.square(enemyRange.getValue().doubleValue()) && distSq < bestDistSq) {
                bestDistSq = distSq;
                targetPlayer = player;
            }
        }
    }

    if (targetPlayer == null) return false;

    // 1. Kiểm tra nếu AutoCrystal đang có sẵn mục tiêu đặt hợp lệ với sát thương đạt chuẩn
    PlaceTarget existingPlacement = this.placeTarget;
    if (existingPlacement != null && existingPlacement.getPosition() != null) {
        BlockPos pos = existingPlacement.getPosition();
        BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
        if (pos.getY() >= targetFeet.below().getY() && existingPlacement.getDamage() >= getMinimumDamage(targetPlayer, minimumDamage.getValue().floatValue())) {
            return false;
        }
    }

    // ĐOẠN MỚI (Lấy mc.player làm tâm 3x3):
    BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
    BlockPos block1 = targetFeet.below();

    Set<BlockPos> candidatePositions = new LinkedHashSet<>();
    for (Direction dir : Direction.Plane.HORIZONTAL) {
        Direction right = dir.getClockWise();

        // --- Vòng trong (bán kính 1 block - 8 ô) ---
        candidatePositions.add(block1.relative(dir));                    // 4 ô thẳng gần
        candidatePositions.add(block1.relative(dir).relative(right));    // 4 ô chéo gần

        // --- Vòng ngoài (bán kính 2 block - 16 ô) ---
        candidatePositions.add(block1.relative(dir, 2));                 // 4 ô thẳng xa
        candidatePositions.add(block1.relative(dir, 2).relative(right));   // 4 ô chữ L (2 thẳng, 1 ngang)
        candidatePositions.add(block1.relative(dir).relative(right, 2));   // 4 ô chữ L (1 thẳng, 2 ngang)
        candidatePositions.add(block1.relative(dir, 2).relative(right, 2)); // 4 ô góc chéo xa nhất (2, 2)
    }

    // 3. [PRE-CHECK]: Kiểm tra xem quanh enemy ĐÃ CÓ bệ Obsidian/Bedrock thực sự hiệu quả chưa
    for (BlockPos pos : candidatePositions) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.BEDROCK) {
            // Không tính bệ nằm xa hơn khoảng cách giữa bạn và enemy
            if (mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > mc.player.distanceToSqr(targetPlayer)) {
                continue;
            }

            BlockPos crystalSpace = pos.above();
            BlockState spaceState = mc.level.getBlockState(crystalSpace);
            if ((spaceState.isAir() || spaceState.canBeReplaced()) && mc.level.getFluidState(crystalSpace).isEmpty()) {
                boolean hasEntity = mc.level.getEntities((Entity) null, new AABB(crystalSpace),
                        entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal)).stream().anyMatch(Entity::isAlive);

                if (!hasEntity) {
                    float existingDamage = DamageUtils.getCrystalDamage(targetPlayer, PositionUtils.extrapolate(targetPlayer, extrapolation.getValue().intValue()), pos, null, ignoreTerrain.getValue());
                    
                    // [TỐI ƯU 5x5]: Bệ có sẵn phải đạt đủ sát thương cài đặt (hoặc >= 4.5f) mới được coi là bệ hợp lệ
                    float minDmg = getMinimumDamage(targetPlayer, minimumDamage.getValue().floatValue());
                    if (existingDamage >= minDmg || existingDamage >= 4.5f) {
                        return false; // Đã có bệ gây sát thương lớn -> Dừng đặt
                    }
                }
            }
        }
    }

    // 4. Nếu CHƯA CÓ bệ hợp lệ nào, tiến hành tìm ô đất/air tốt nhất để đặt Obsidian
    BlockPos bestCandidate = null;
    double bestScore = Double.MAX_VALUE;

    for (BlockPos pos : candidatePositions) {
        if (!isBaseCandidateValid(pos)) continue;

        BlockState state = mc.level.getBlockState(pos);
        // Bỏ qua các ô đã là Obsidian/Bedrock
        if (state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.BEDROCK) continue;

        // Kiểm tra an toàn cho bản thân
        if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
            float selfDamage = DamageUtils.getCrystalDamage(mc.player, null, pos, null, ignoreTerrain.getValue());
            if (selfDamage > maximumSelfDamage.getValue().floatValue()) continue;
            if (antiSuicide.getValue() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
        }

        // Tính sát thương gây ra cho mục tiêu
        float targetDamage = DamageUtils.getCrystalDamage(targetPlayer, PositionUtils.extrapolate(targetPlayer, extrapolation.getValue().intValue()), pos, null, ignoreTerrain.getValue());
        if (targetDamage < 1.5f) continue;

        double score = -targetDamage * 10.0 + mc.player.distanceToSqr(Vec3.atCenterOf(pos)) + targetPlayer.distanceToSqr(Vec3.atCenterOf(pos));
        if (score < bestScore) {
            bestScore = score;
            bestCandidate = pos;
        }
    }

    if (bestCandidate == null) return false;

    int obsidianSlot = basePlaceSwitch.getValue().equalsIgnoreCase("None") ? -1 :
            InventoryUtils.find(Items.OBSIDIAN, 0, basePlaceSwitch.getValue().equalsIgnoreCase("AltSwap") || basePlaceSwitch.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
    if (obsidianSlot == -1) return false;

    Direction placeDir = WorldUtils.getDirection(bestCandidate, false);
    if (placeDir == null) placeDir = WorldUtils.getClosestDirection(bestCandidate, true);
    if (placeDir == null) placeDir = Direction.UP;

    int prevSlot = mc.player.getInventory().getSelectedSlot();
    InventoryUtils.switchSlot(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);
    boolean placed = WorldUtils.placeBlock(bestCandidate, placeDir, InteractionHand.MAIN_HAND,
            basePlaceRotate.getValue(), true,
            renderMode.getValue().equalsIgnoreCase("Both") || renderMode.getValue().equalsIgnoreCase("Fill"));
    InventoryUtils.switchBack(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);

    return placed;
}

    private boolean isBaseCandidateValid(BlockPos pos) {
        if (mc.level == null) return false;
        if (mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > Mth.square(basePlaceRange.getValue().doubleValue())) return false;

        BlockState state = mc.level.getBlockState(pos);
        boolean isObsidianOrBedrock = state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == Blocks.BEDROCK;
        if (!isObsidianOrBedrock && !WorldUtils.isPlaceable(pos)) return false;

        BlockPos crystalSpace = pos.above();
        BlockState spaceState = mc.level.getBlockState(crystalSpace);
        if (!spaceState.isAir() && !spaceState.canBeReplaced()) return false;
        if (!mc.level.getFluidState(crystalSpace).isEmpty()) return false;

        if (placements.getValue().equalsIgnoreCase("Protocol")) {
            BlockState spaceState2 = mc.level.getBlockState(pos.above(2));
            if (!spaceState2.isAir() && !spaceState2.canBeReplaced()) return false;
        }

        return mc.level.getEntities((Entity) null, getCrystalPlacementBox(pos), entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal)).isEmpty();
    }

    private AABB getCrystalPlacementBox(BlockPos position) {
        return new AABB(position.offset(0, 1, 0));
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