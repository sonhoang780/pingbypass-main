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
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.PositionUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.rotations.RotationUtils;
import eu.client.utils.system.Counter;
import eu.client.utils.system.Timer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
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

    public CategorySetting miscellaneousCategory = new CategorySetting("Miscellaneous", "The category for all miscellaneous settings.");
    public ModeSetting sequential = new ModeSetting("Sequential", "The sequence that the module's processes will be run in.", new CategorySetting.Visibility(miscellaneousCategory), "Strong", new String[]{"None", "Strict", "Strong"});
    // 2026-08-14: reverted to master (github.com/sonhoang780/pingbypass-main) + diffed against
    // bản gốc 1.21.4 (Desktop copy). "MovementSync" was a ClientRotationEvent-arbitrated rewrite of
    // "Normal" invented entirely within this session's branch, never merged to master and with no
    // equivalent in bản gốc -- removed outright rather than kept as a third option. Normal/Packet
    // below, and every call site using them, are bản gốc 1.21.4's originals: one yaw swap, only for
    // the UpdateMovementEvent -> Post window that encloses sendPosition, real input and local
    // physics untouched. legacyRotate()/legacyQueue is RotationManager's verbatim port of bản gốc's
    // own rotate()/PriorityBlockingQueue<Rotation> -- same method, kept under its port-era name.
    public ModeSetting rotate = new ModeSetting("Rotate", "Automatically rotates to the crystal whenever attacking or placing.", new CategorySetting.Visibility(miscellaneousCategory), "Normal", new String[]{"None", "Normal", "Packet", "Silent"});
    public ModeSetting swing = new ModeSetting("Swing", "The hand that will be used for swinging.", new CategorySetting.Visibility(miscellaneousCategory), "Default", new String[]{"Default", "None", "Packet", "Mainhand", "Offhand", "Both"});
    public BooleanSetting yawStep = new BooleanSetting("YawStep", "Performs your rotations over multiple ticks.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public NumberSetting yawStepThreshold = new NumberSetting("YawStepThreshold", "Threshold", "The threshold in order for yaw to be modified.", new BooleanSetting.Visibility(yawStep, true), 75, 1, 180);
    public BooleanSetting raytrace = new BooleanSetting("Raytrace", "Avoids attacking or placing any crystals through walls.", new CategorySetting.Visibility(miscellaneousCategory), false);
    // Multitarget scans every candidate position against EVERY player in getPlayers() (see
    // calculateCrystals/calculatePlacements) -- O(positions * players), and calculatePlacements
    // deliberately doesn't early-exit (see the comment on that method), so cost scales close to
    // linearly with player count. Async execution (see `asynchronous` below) keeps that off the
    // render thread on a normal tick, but onDestroyBlock's own recompute (blockDestruction) can
    // still land on it synchronously in the async-off fallback -- and either way, more targets is
    // strictly more work somewhere. "All" keeps the existing behavior (best damage across every
    // player); the other three collapse the player list to exactly one candidate before the
    // position scan even starts, turning it back into O(positions) regardless of how many
    // opponents are actually nearby.
    public ModeSetting targetMode = new ModeSetting("Target", "Which player to target when multiple are in range -- narrowing this to one collapses the per-position player scan from O(players) to O(1), the actual fix for multitarget FPS drops.", new CategorySetting.Visibility(miscellaneousCategory), "All", new String[]{"All", "Nearest", "Farthest", "Health"});
    public NumberSetting extrapolation = new NumberSetting("Extrapolation", "Extrapolates the target's position to calculate positions ahead of time.", new CategorySetting.Visibility(miscellaneousCategory), 0, 0, 20);
    public NumberSetting enemyRange = new NumberSetting("EnemyRange", "The maximum distance at which enemies can be at.", new CategorySetting.Visibility(miscellaneousCategory), 10.0, 0.0, 24.0);
    public BooleanSetting chestBreak = new BooleanSetting("ChestBreak", "Prevents other players from getting obsidian from ender chests by destroying the dropped items.", new CategorySetting.Visibility(miscellaneousCategory), false);
    // Pre-places a crystal on (or beside) whatever SpeedMine is currently mining, a few ticks
    // before that block actually breaks, then detonates it the instant the block is gone -- so the
    // explosion lands right as the support block disappears instead of racing calculatePlacements'
    // full scan + normal place/attack timers AFTER the fact. Reads SpeedMineModule.getPrimary()'s
    // own progress/speed directly (Action.getTicksRemaining()) rather than duplicating its mining
    // math here.
    public BooleanSetting mineIgnore = new BooleanSetting("MineIgnore", "Pre-places a crystal on the block SpeedMine is about to break, and detonates it the instant that block is gone.", new CategorySetting.Visibility(miscellaneousCategory), false);
    public NumberSetting mineIgnoreTicks = new NumberSetting("MineIgnoreTicks", "Tick", "How many ticks before the block breaks to place the crystal.", new BooleanSetting.Visibility(mineIgnore, true), 3, 0, 10);
    public BooleanSetting asynchronous = new BooleanSetting("Asynchronous", "Performs calculations on separate threads.", new CategorySetting.Visibility(miscellaneousCategory), true);
    // 2026-08-15 (requested): "AutoCrystal khi thấy block secondary đã tới thời điểm vỡ thì dừng
    // lại, để block kia vỡ rồi mới autocrystal tiếp". Both SpeedMine secondary models are covered
    // (FarReach's Secondary hold/release latch vs the legacy dual-Action pair), same branch
    // mineTargetSecondary already uses to tell which one is live.
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

    // Animated ESP shaders ported from Sydney-Legacy's "Shaderz" module. Sydney kept these on a
    // separate hidden CORE module with a per-mode speed slider each (GradSpeed/RainSpeed/GarmSpeed/
    // ...), every one of them the exact same 0.1-10 multiplier feeding the exact same `time` uniform
    // -- nine sliders for one number. Collapsed into a single Speed here and hung off the existing
    // Render category, where every other AutoCrystal render dial already lives, rather than adding a
    // whole module for settings that only AutoCrystal reads.
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
    // executor's queue is unbounded and nothing here ever cancels/drains it -- if a task ever
    // takes longer than the tick interval (GC pause, toggling off mid-task then back on
    // immediately resubmits without the old one having finished, etc.) tasks pile up and each
    // toggle-off/on cycle can leave more of a backlog than the last, showing up later as
    // AutoCrystal getting progressively slower for no visible reason. Track the in-flight task
    // and skip resubmitting while one is still running instead of queueing another -- only the
    // latest calculation is ever useful anyway.
    private volatile java.util.concurrent.Future<?> pendingCalc = null;
    // 2026-08-15: see onUpdateMovement's own doc -- the background executor thread now keeps
    // itself running continuously (each pass immediately resubmits the next one) instead of
    // waiting for the next GAME TICK to even consider resubmitting. This flag is the loop's own
    // off-switch: onDisable()/async-toggled-off clears it so the chain actually stops instead of
    // resubmitting forever.
    private volatile boolean asyncLoopActive = false;
    // Reserved-cell snapshot, refreshed every tick on the main thread (cheap: WorldManager's own
    // set copy) -- the background loop just reads whatever's newest here each pass instead of
    // being handed a single snapshot per submission. Up to one tick stale at worst, same as
    // before; only POSITION staleness (the actual reported bug) is what the loop change fixes.
    private volatile Set<BlockPos> latestReservedPlacements = Set.of();

    private Runnable attackRunnable = null;
    private Runnable placeRunnable = null;

    private final Map<Integer, Long> attackedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> placedCrystals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> countedCrystals = new ConcurrentHashMap<>();

    private final Timer attackTimer = new Timer();
    private final Timer placeTimer = new Timer();
    private final Timer facePlaceTimer = new Timer();
    private final Timer loopTimer = new Timer();
    // Raw monotonic counters (never TTL-cleared, unlike attackedCrystals/placedCrystals) --
    // ping*2 as the map cleanup TTL means TTL=0 whenever ping reads 0 (e.g. before the first
    // real keepalive round-trip completes early in a session), which empties those maps
    // instantly and made the state log always show 0 regardless of what was really happening.
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
    // 2026-08-15 FIX (reported: "block xanh vỡ trước, block đỏ vỡ sau, AutoCrystal lại đợi 2
    // block vỡ hết mới autocrystal" -- SpeedMine Double breaking 2 blocks at once). mineTarget
    // above is (and always was) computed from SpeedMineModule.getPrimary() ONLY -- see
    // runCalculation. Whichever of primary/secondary happens to break FIRST, if it's the
    // secondary, its position never matched mineTarget's tracked exception at all, so
    // onDestroyBlock's own staleness check discarded it as unrelated and never reacted --
    // looked exactly like "waiting for both", when really it was just never watching the
    // secondary block in the first place. A second tracked target for the secondary closes that.
    private PlaceTarget mineTargetSecondary = null;

    // MineIgnore tracking: which SpeedMine target we've already pre-placed a crystal for, and where
    // that crystal actually landed (may not be directly above minePos -- see mineIgnoreTick).
    // Cleared whenever SpeedMine stops mining that exact block (target changed/cancelled) or once
    // it breaks and the crystal's been detonated.
    private BlockPos mineIgnoreMinedPos = null;
    private BlockPos mineIgnorePlacedPos = null;


    private String calculationTime = "0.00ms";
    private int calculationCount = 0;
    @Getter private String calculationDamage = "0.00";

    private final Counter crystalCounter = new Counter();
    private int crystalsPerSecond = 0;

    private int highestID = -100000;
    private int kickTicks = 0;

    // Ported from Sydney-Legacy: getPlayers() rebuilt the FULL player list (mc.level.players()
    // iteration + isAlive + distance + FRIEND_MANAGER string lookup per player) from scratch on
    // every call -- and it's called up to 3x per tick here (attack calc, place calc, and again for
    // blockDestruction's mineTarget calc), all within the same tick where the world state hasn't
    // meaningfully changed. Cache for a short TTL (50ms, same as Sydney -- short enough that a
    // player actually moving/dying/leaving range is still picked up within a couple of ticks, long
    // enough to collapse those redundant rebuilds into one per real update).
    private List<Player> cachedPlayers = new ArrayList<>();
    private long lastPlayerCacheTime = 0L;
    private static final long PLAYER_CACHE_DURATION = 50L;

    // SwapBack (Switch=Normal only): captured the FIRST time we switch away from a non-crystal item
    // and left alone on every placement after that -- placeCrystals() reads the CURRENT selected slot
    // as "previousSlot" each call, which after the first switch would just be the crystal slot itself,
    // not the real original item. Restored once, when the module is disabled, not after every place.
    private int savedSlot = -1;

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;

        // ping*2 alone has no floor -- at low/near-zero ping (e.g. a VPS colocated with the
        // target server) this drops to a handful of ms, LESS than the ~50ms minimum the real
        // server needs just to process the action and tick out a response (one server tick,
        // regardless of network latency). The tracking entry then expires before the server's
        // own confirmation could ever arrive, making "did I just place/attack this" unreliable
        // on a per-action basis -- sometimes the timing lines up and it works, sometimes it
        // doesn't, which is exactly what shows up as CPS randomly fluctuating even with a
        // perfectly stable, near-zero connection. Floor it at one server tick's worth.
        long minTtl = 50L;
        attackedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, minTtl));
        // placedCrystals specifically has to OUTLIVE the round trip it exists to bridge:
        // place() records the position, and onEntitySpawn/calculateCrystals then require
        // placedCrystals.containsKey(crystal.blockPosition().below()) to recognise the crystal
        // the server eventually spawns there as "ours" and instant-attack it. That spawn packet
        // can't come back faster than (proxy->server + one server tick + server->proxy), which
        // on any real connection is well past the 50ms floor the other two maps use -- so the
        // entry was usually already evicted by the time its own crystal arrived. The
        // instant-attack never matched, the Strong-sequential place->attack->place chain never
        // started, and the one crystal sitting there unattacked became an "obstruction" in
        // calculatePlacements after 15 ticks, blocking that position for good: place exactly one
        // crystal, then stall. Give this map a floor that actually covers a full round trip.
        long placedTtl = Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, 500L)
                + (long) ((20 - attackSpeed.getValue().floatValue()) * 50L);
        // Don't drop an expired entry while a live EndCrystal is STILL sitting exactly where we
        // placed one -- that used to permanently strand it. attackCrystals()'s main scan (used
        // whenever some OTHER placement target is also available this tick, i.e. flag=false)
        // only recognises a crystal as "ours" via this map; once the entry aged out, an
        // un-detonated self-placed crystal (target moved away before the attack landed, attack
        // briefly blocked by something else, whatever delayed it past the round-trip TTL) became
        // indistinguishable from a stranger's crystal -- skipped by the main scan forever, and
        // only reachable by the obstruction fallback, which itself only runs when NO alternative
        // placement exists. Reported: a crystal sitting untouched while the module moved on to
        // place fresh ones elsewhere, clearable only by attacking it by hand. Keep refreshing the
        // timestamp instead of removing as long as the crystal is provably still there and alive.
        placedCrystals.entrySet().removeIf(entry -> {
            if (System.currentTimeMillis() - entry.getValue() <= placedTtl) return false;

            boolean stillLive = mc.level.getEntities((Entity) null, new AABB(entry.getKey().above()), e -> e instanceof EndCrystal)
                    .stream().anyMatch(Entity::isAlive);
            if (stillLive) {
                // JDK25 ConcurrentHashMap.removeIf tests against a throwaway immutable
                // entry (AbstractMap$SimpleImmutableEntry) -- setValue() on it throws
                // UnsupportedOperationException. Write back through the map itself instead.
                placedCrystals.put(entry.getKey(), System.currentTimeMillis());
                return false;
            }
            return true;
        });
        countedCrystals.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > Math.max(EUClient.SERVER_MANAGER.getPing() * 2L, minTtl));

        crystalsPerSecond = crystalCounter.getCount();

        if (gameLoop.getValue()) return;

        run();
    }

    // The actual calc-trigger used to sit in onPlayerUpdate above, right where the TTL cleanup
    // is -- but WorldManager.getReservedPlacements() is what stops this module and a placement
    // module (SelfFill/Surround/...) whack-a-moling the same cell, and that only works if the
    // OTHER module has already reserved its cell for the tick before this one reads it. Both
    // this module and Surround/SelfFill subscribe to the SAME PlayerUpdateEvent -- with the calc
    // submitted (async) from INSIDE that handler, the background thread could start reading
    // getReservedPlacements() while OTHER modules' own PlayerUpdateEvent handlers for this same
    // tick hadn't run yet (dispatch order between modules isn't something either side controls),
    // seeing an empty/stale set and picking the exact cell Surround was about to reserve --
    // reported as Surround's render position flickering nonstop despite the reservation check
    // existing. UpdateMovementEvent fires later, from the player entity's own tick, strictly
    // after every module's PlayerUpdateEvent handler for this tick has already run -- moving the
    // submission here (not the attack/place EXECUTION further below, unrelated) guarantees any
    // reservation made this tick is visible before the read.
    // 2026-08-15 FIX (reported: can't place/break while moving with Asynchronous on -- target
    // chronically stale/out-of-range). The old design resubmitted the background calc at most
    // once per GAME TICK, and only once the PREVIOUS pass had finished -- so freshness was capped
    // at "once per completed calculation", and every tick in between kept reading a
    // placeTarget/mineTarget computed against wherever the player WAS during the last pass, not
    // where they are now. calculatePlacements already reads mc.player's position LIVE, every
    // iteration -- the actual fix is keeping the background thread running CONTINUOUSLY (each
    // pass immediately kicks off the next one, see asyncLoopStep) instead of gating resubmission
    // on tick timing. Freshness is now bounded by how fast one scan completes, not by the tick
    // rate -- still entirely off the main/render thread, which only ever does the cheap
    // reservedPlacements snapshot below.
    @SubscribeEvent
    public void onUpdateMovement(UpdateMovementEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;

        mineIgnoreTick();

        // Snapshot NOW, synchronously, on the main thread -- see the big comment on the
        // (BlockPos, Set<BlockPos>) overload of calculatePlacements for why a live read from
        // inside the background loop is a race against WorldManager's per-tick clear(). Refreshed
        // every tick regardless of where the loop currently is -- its next pass just picks up
        // whatever's newest here.
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

    // One pass, then immediately resubmits itself (still on the SAME single background thread --
    // never more than one calculation in flight, same invariant the old isDone() gate enforced,
    // just without waiting for a tick to re-arm it) as long as the module still wants async
    // running. asyncLoopActive is the off-switch onDisable()/the sync-mode branch above use to
    // actually stop the chain instead of it resubmitting forever.
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

        // Verbatim original condition (blockDestruction && asynchronous) -- in the sync branch
        // this is always skipped, matching the old behavior exactly: onDestroyBlock's own
        // `stale && !asynchronous.getValue()` fallback is what keeps mineTarget fresh when async
        // is off, not this method.
        if (blockDestruction.getValue() && async) {
            SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
            BlockPos position = null;

            if (module.getPrimary() != null && module.getPrimary().isMining()) position = module.getPrimary().getPosition();
            if (position != null) mineTarget = calculatePlacements(position, reservedPlacements);

            // 2026-08-15 FIX: was primary-only -- see mineTargetSecondary's own field doc. Double
            // mode's second block lives in a different field depending on FarReach (Secondary's
            // hold/release latch model vs the plain dual-Action legacySecondary), same branch
            // onPlayerUpdate itself already uses to decide which one is live.
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
        if (mc.player == null || mc.level == null) return;
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
        if (pauseForSecondaryMine() || rotationOwnedByOther()) return;

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

    // 2026-08-15 (requested): let SpeedMine's secondary block finish breaking before AutoCrystal
    // fires again, instead of racing it. Secondary (FarReach on) has no getTicksRemaining() of
    // its own -- getProgress() (0..1, clamped) is the closest equivalent, so that branch uses a
    // ratio threshold instead of an exact tick count.
    private static final int SECONDARY_PAUSE_TICKS = 2;
    private static final float SECONDARY_PAUSE_PROGRESS = 0.85f;

    private boolean pauseForSecondaryMine() {
        if (!pauseOnSecondaryMine.getValue()) return false;

        SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
        if (module.farReach.getValue()) {
            SpeedMineModule.Secondary secondary = module.getSecondary();
            return secondary != null && secondary.getProgress() >= SECONDARY_PAUSE_PROGRESS;
        } else {
            SpeedMineModule.Action legacySecondary = module.getLegacySecondary();
            return legacySecondary != null && legacySecondary.isMining() && legacySecondary.getTicksRemaining() <= SECONDARY_PAUSE_TICKS;
        }
    }

    // 2026-08-15: the Rotate=Normal + MovementFix + Sprint=Grim + AutoCrystal diagonal-flag combo
    // (see this file's git history / SESSION_2026-08-15_VIA121X_FLAG_FIX.md) is worked around by
    // deferring AutoCrystal's action a whole tick whenever Sprint's Grim mode won rotation
    // arbitration that tick. Was briefly removed on request (theory: MovementFix already keeps
    // position/yaw resynced the same way Sprint=Legit does, so the defer costs nothing needed) --
    // restored after the user retested and confirmed the flag came back, specifically on native
    // 1.21.x (never reproduced on a ViaFabricPlus 1.20.x downgrade, matching the original report's
    // own scoping). MovementFix does not substitute for this: it fixes movement DIRECTION
    // consistency, not the RotationPlace validation this defer exists for (GrimAC's own
    // config.yml: `RotationPlace: cancelvl: 5`, checked against the player's last-KNOWN rotation
    // at action time -- Sprint winning that tick's arbitration leaves the wire rotation pointed at
    // Sprint's target, not AutoCrystal's, regardless of whether movement direction was correct).
    private boolean rotationOwnedByOther() {
        if (!rotate.getValue().equalsIgnoreCase("Normal")) return false;
        Module owner = EUClient.ROTATION_MANAGER.getRotationOwner();
        return owner != null && owner != this;
    }

    @SubscribeEvent
    public void onUpdateMovement$POST(UpdateMovementEvent.Post event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;

        if (attackRunnable != null) attackRunnable.run();
        if (placeRunnable != null) placeRunnable.run();
    }

    @SubscribeEvent
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;

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
        if (rotate.getValue().equalsIgnoreCase("Normal") && !EUClient.ROTATION_MANAGER.isRotationReached(entityAttackRotations)) return;

        attack(crystal);

        attackedSequentially = true;
        if (sequential.getValue().equalsIgnoreCase("Strong")) {
            placeCrystals(true);
        }
    }

    @SubscribeEvent
    public void onDestroyBlock(DestroyBlockEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        if (mc.player == null || mc.level == null) return;

        kickTicks = 0;

        // Independent of blockDestruction below -- MineIgnore pre-places on the block BEFORE it
        // breaks (see mineIgnoreTick()), so by the time this fires the crystal's already down and
        // waiting; all that's left is detonating it, immediately, no timer/switch/range gate (the
        // whole point is landing the hit the instant the support block is gone, not racing the
        // normal attack pipeline's own timers for it).
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

        // calculatePlacements() is the full, non-early-exit radius x players scan (see the big
        // comment on that method) -- fine off the async executor thread (onUpdateMovement already
        // recomputes it there every tick when blockDestruction+asynchronous are both on), but
        // DestroyBlockEvent fires on whatever thread SpeedMine's own block-breaking runs on (the
        // main/render thread), repeatedly, every block broken. Recomputing it HERE too meant that
        // cost landed on the render thread directly, scaling with target count -- reported as severe
        // FPS drop with multiple real players nearby. Only fall back to a synchronous recompute when
        // there genuinely isn't an async one already keeping this fresh.
        //
        // 2026-08-15 FIX (reported: "SpeedMine Double, block xanh vỡ trước, block đỏ vỡ sau, mà
        // AutoCrystal đợi 2 block vỡ hết mới phản ứng"). This used to check ONLY this.mineTarget,
        // which runCalculation only ever populates from SpeedMine's PRIMARY -- whichever block
        // broke FIRST, if it was the secondary, its position never matched and got treated as
        // stale/unrelated every time, so AutoCrystal silently never reacted to it at all (looked
        // like "waiting for both" when it was really "only ever watching one of the two"). Try
        // BOTH tracked targets against the block that actually just broke.
        PlaceTarget primaryCandidate = this.mineTarget == null ? null : this.mineTarget.clone();
        PlaceTarget secondaryCandidate = this.mineTargetSecondary == null ? null : this.mineTargetSecondary.clone();

        PlaceTarget mineTarget;
        if (primaryCandidate != null && primaryCandidate.getPosition() != null && minedPosition.equals(primaryCandidate.getException())) {
            mineTarget = primaryCandidate;
        } else if (secondaryCandidate != null && secondaryCandidate.getPosition() != null && minedPosition.equals(secondaryCandidate.getException())) {
            mineTarget = secondaryCandidate;
        } else if (!asynchronous.getValue()) {
            // blockDestruction is already guaranteed true here (checked above) -- asynchronous is
            // the only thing deciding whether onUpdateMovement is already keeping these fresh off
            // this thread.
            mineTarget = calculatePlacements(minedPosition);
        } else {
            // Async on, but neither tracked target's last snapshot was for this exact block (its
            // own async pass just hasn't landed yet) -- fall back to whichever still exists
            // rather than dropping the reaction outright.
            mineTarget = primaryCandidate != null ? primaryCandidate : secondaryCandidate;
        }
        if (mineTarget == null || mineTarget.getPosition() == null) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        BlockPos position = mineTarget.getPosition();

        // 2026-08-15 FIX (reported: "place render đặt chỗ xa lắc xa lơ" while moving, correct
        // while standing still). calculatePlacements can run off the async executor thread (see
        // its own doc) and land here a tick or more after the target was actually computed --
        // while moving, the player can easily have covered several blocks in that gap, so a
        // position that WAS in range the moment it was calculated is stale and out of PlaceRange
        // by the time it gets here. The range check below already correctly refuses to ACT on a
        // stale target (no packet ever goes out for it) -- the bug was only that the render call
        // used to run BEFORE that check, so the stale ghost position still got drawn every time,
        // even though nothing was ever placed there. Standing still hides the bug entirely
        // (position never goes stale if the player never moves), which is exactly why it only
        // showed up while moving. Not Rotate=Silent's doing -- Silent never touches mc.player's
        // real position, this is the same staleness for every Rotate mode.
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }
        EUClient.RENDER_MANAGER.setRenderPosition(position);
        // `position` is the SOLID support block (obsidian/bedrock) the crystal sits on. Plain
        // canSee(position) raycasts to that block's own center, so it's hitting that exact block
        // and never reports MISS -- "!canSee" was true unconditionally for every candidate, so
        // beyond PlaceWallsRange (4.5 blocks by default -- easy to be past that just standing
        // normally near a target) Raytrace's fallback gate silently rejected every single
        // placement regardless of actual walls. A prior fix raycast to position.above() instead
        // (the air cell the crystal spawns into) -- but that demands line of sight to empty air a
        // real player never needs: vanilla lets you place by clicking ANY visible face of the
        // support block, underside included (standing directly under an overhang and placing on
        // the block above your head works by hand, reported broken here). canSeeBlock(position)
        // checks the actual thing that matters -- is there a WALL between the eye and this block,
        // not "is the specific cell above it in view".
        if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue())))
            return;

        float[] placeRotations = calculateRotations(Vec3.atCenterOf(position).add(0, 1, 0));
        if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(placeRotations, this, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this) + 1); // place outranks attack, same +1 as ban goc 1.21.4
        // 2026-08-15: was `!equalsIgnoreCase("None")` (bản gốc 1.21.4's own verbatim behaviour --
        // fires packetRotate at this SAME target even in Normal mode, on top of the legacyRotate
        // line above). Deliberate deviation from bản gốc, on request: that duplicate is an EXTRA
        // ServerboundMovePlayerPacket.PosRot outside the tick's normal sendPosition() cadence --
        // invisible to a 1.20.x-downgraded connection (ViaFabricPlus strips
        // ServerboundClientTickEndPacket, so the server has no per-tick packet count to compare
        // against) but exposed on a 1.21.x connection (native tick-end marker lets the server see
        // an extra position-carrying packet landed outside the accounted-for tick) -- reported
        // live as "Normal + MovementFix ổn ở via 1.20.x, flag khi di chuyển + AutoCrystal ở via
        // 1.21.x". legacyRotate's own queued rotation already reaches the wire next tick through
        // the ordinary sendPosition() packet; Packet mode has no legacyRotate call at all and
        // still needs this line as its only delivery mechanism.
        if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), placeRotations);

        for (Entity entity : mc.level.getEntities((Entity) null, new AABB(position.above()), entity -> true).stream().filter(entity -> entity instanceof EndCrystal).toList()) {
            float[] entityRotations = RotationUtils.getRotations(entity);
            if (!rotate.getValue().equalsIgnoreCase("None")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), entityRotations);
            // See RotationManager.isRotationReached's own doc -- Normal only, Packet/Silent already
            // sent synchronously on the line above.
            if (rotate.getValue().equalsIgnoreCase("Normal") && !EUClient.ROTATION_MANAGER.isRotationReached(entityRotations)) break;

            mc.player.connection.send(new ServerboundAttackPacket(entity.getId()));
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

            break;
        }

        SpeedMineModule module = EUClient.MODULE_MANAGER.getModule(SpeedMineModule.class);
        boolean flag = module.switchReset.getValue() && (module.switchMode.getValue().equalsIgnoreCase("Normal") || module.switchMode.getValue().equalsIgnoreCase("AltSwap") || module.switchMode.getValue().equalsIgnoreCase("AltPickup"));

        if (rotate.getValue().equalsIgnoreCase("Normal") && !EUClient.ROTATION_MANAGER.isRotationReached(placeRotations)) return;

        if (!autoSwitch.getValue().equalsIgnoreCase("None") &&  mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
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
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        kickTicks = 0;
    }

    @SubscribeEvent
    public void onClientConnect(ClientConnectEvent event) {
        if (eu.client.pingbypass.PingBypassFlags.isPingBypassActive()) return;
        highestID = -100000;
    }

    @Override
    public void onDisable() {
        // Must clear BEFORE cancel() -- asyncLoopStep only stops resubmitting once it observes
        // this false, so setting it after would still let an in-flight pass queue one more.
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
        EndCrystal overrideCrystal = null;

        // obstructions accumulates EVERY blocked candidate scanned, not just whatever's in the
        // way of the spot we actually ended up placing at. `flag` switches the SCAN SOURCE to
        // obstructions-only for the case nothing could be placed anywhere; below that, `isKnown`
        // separately recognizes an obstruction candidate as attack-worthy even while scanning
        // entitiesForRendering() with getPosition() != null (an alternate spot WAS found).
        //
        // Was: only ever attack-worthy when getPosition() == null (nothing placeable anywhere).
        // A crystal blocking one candidate (someone else's, a stale manually-placed one, whatever)
        // that ISN'T also in placedCrystals got permanently ignored the instant calculatePlacements
        // found ANY other valid spot for the target -- reported live: a FakePlayer target standing
        // still, a manually-placed crystal sitting right next to it (never went through place(),
        // so never entered placedCrystals), AutoCrystal found a different spot elsewhere and just
        // left the manual crystal alone forever, attackable only by hand. calculatePlacements
        // still recorded it in pt.obstructions every single cycle (see its own `exception == null
        // && !obstructingCrystals.isEmpty()` branch, unconditional on whether optimalPosition was
        // also found) -- this just never consulted that list unless it was the ONLY thing in `pt`.
        // Attacking a real, currently-blocking obstruction doesn't cost anything even when a
        // different spot also worked -- it's a separate action, not a hijack -- so recognize it
        // here too instead of only in the getPosition()==null fallback.
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
        else if (rotate.getValue().equalsIgnoreCase("Normal") && !EUClient.ROTATION_MANAGER.isRotationReached(attackTargetRotations)) bailReason = "rotation-not-reached";

        if (bailReason != null) return;
        attackRunnable = () -> {
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));

            attack(crystal);
        };
    }

    private void placeCrystals(boolean sequential) {
        PlaceTarget placeTarget = this.placeTarget == null ? null : this.placeTarget.clone();
        if (placeTarget == null || placeTarget.getPosition() == null) {
            EUClient.RENDER_MANAGER.setRenderPosition(null);
            return;
        }

        int slot = InventoryUtils.findHotbar(Items.END_CRYSTAL);
        int previousSlot = mc.player.getInventory().getSelectedSlot();

        if (!autoSwitch.getValue().equalsIgnoreCase("None") && slot == -1 && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL))
            return;

        BlockPos position = placeTarget.getPosition();

        // See onDestroyBlock's identical 2026-08-15 fix for the full rationale -- render position
        // is only committed once the target has been confirmed still in range, so a target that
        // went stale (calculatePlacements ran a tick or more ago, off the async executor thread,
        // and the player has since moved) never draws a ghost placement out at its old distance.
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

        float[] placeTargetRotations = calculateRotations(Vec3.atCenterOf(position).add(0, 1, 0));
        if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(placeTargetRotations, EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));

        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;
        if (!sequential && placedSequentially) {
            placedSequentially = false;
            return;
        }
        if (rotate.getValue().equalsIgnoreCase("Normal") && !EUClient.ROTATION_MANAGER.isRotationReached(placeTargetRotations)) return;

        placeRunnable = () -> {
            boolean switched = false;

            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(position).add(0, 1, 0)));

            if (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
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

    // See calculatePlacements' own comment on stickyPosition.
    private static final float STICKY_EPSILON = 0.5f;

    private PlaceTarget calculatePlacements(BlockPos exception) {
        return calculatePlacements(exception, EUClient.WORLD_MANAGER.getReservedPlacements());
    }

    // Split out so onUpdateMovement can hand this a SNAPSHOT of the reserved set instead of the
    // live one. WorldManager.reservedPlacements is cleared every tick at Minecraft.tick() HEAD --
    // if this whole method runs on the async executor thread (the default), it can execute an
    // arbitrary amount of wall-clock time after being submitted, easily longer than one tick under
    // real load (multitarget scans especially). By the time a live getReservedPlacements() read
    // actually happens, the NEXT tick's clear() may have already wiped the exact reservation a
    // placement module (Surround/SelfTrap/...) made THIS tick -- so this module would see an empty
    // set and freely place a crystal on the cell the player is actively trying to place a real
    // block into. Capturing the set synchronously, on the main thread, at submission time (before
    // the async lambda ever runs) closes that race entirely.
    private PlaceTarget calculatePlacements(BlockPos exception, Set<BlockPos> reservedPlacements) {
        if (!place.getValue()) return null;

        if (shouldPause("Place") || ((autoSwitch.getValue().equalsIgnoreCase("None") || InventoryUtils.findHotbar(Items.END_CRYSTAL) == -1) && (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL)))
            return null;

        List<Player> players = selectTargets(getPlayers());
        if (players.isEmpty()) return null;

        BlockPos optimalPosition = null;
        Player optimalPlayer = null;
        List<Entity> obstructions = new ArrayList<>();
        float optimalDamage = 0.0f;

        // See ServerAutoCrystal's identical fix for the full rationale (ported 1:1, same bug hit
        // independently in both places): two candidates at genuinely equal (or near-equal,
        // floating point) damage each traded the lead every cycle as the target moved even
        // slightly, so the module kept re-committing to a different position instead of just
        // placing. STICKY_EPSILON boosts whichever candidate matches last cycle's committed
        // position so a challenger needs to actually be better, not just edge it out by noise.
        BlockPos stickyPosition = this.placeTarget == null ? null : this.placeTarget.getPosition();

        int calculations = 0;

        // Self-origin nearest-first WITH a break-on-first-lethal early exit (matching the
        // pre-port 1.21.4 implementation) kept picking whichever candidate happened to be
        // scanned first that merely cleared the target's HP -- against a target that's easy to
        // one-shot from almost anywhere nearby (low HP, or just not enough armor), that's very
        // often a spot right next to the PLAYER rather than anywhere near the actual target, since
        // the scan starts at self and radiates outward. A softer "overkill margin" threshold on
        // the break helped somewhat but didn't fix the actual defect: the break exits before ever
        // comparing against the genuinely best candidate. Scans the full candidate set now (no
        // early exit) and keeps only the single highest-damage one -- this runs off the async
        // executor thread already (not the render thread), so the extra candidates cost latency,
        // not FPS.
        for (int i = 0; i < EUClient.WORLD_MANAGER.getRadius(Math.max(placeRange.getValue().doubleValue(), placeWallsRange.getValue().doubleValue())); i++) {
            BlockPos position = mc.player.blockPosition().offset(EUClient.WORLD_MANAGER.getOffset(i));

            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeRange.getValue().doubleValue())) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(position)) continue;
            if (mc.level.getBlockState(position).getBlock() != Blocks.OBSIDIAN && mc.level.getBlockState(position).getBlock() != Blocks.BEDROCK) continue;
            if (!mc.level.getBlockState(position.offset(0, 1, 0)).isAir() || (placements.getValue().equalsIgnoreCase("Protocol") && !mc.level.getBlockState(position.offset(0, 2, 0)).isAir())) continue;

            if (!WorldUtils.canSeeBlock(position) && (raytrace.getValue() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) > Mth.square(placeWallsRange.getValue().doubleValue()))) continue;

            // A placement module (SelfFill/Surround/...) reserved a cell this tick, meaning it's
            // actively trying to place a real block there. A crystal here (2x2x2 hitbox, spans the
            // block it sits on plus one block up and inflates 1 block horizontally) blocks the
            // server's own placement raytrace even without directly overlapping the target cell --
            // and since this module never voluntarily gives up an optimal spot, without this check
            // it whack-a-moles that cell forever against destroyCrystals. Skip the candidate outright.
            AABB crystalBox = new AABB(position.getX() - 1, position.getY() + 1, position.getZ() - 1, position.getX() + 2, position.getY() + 3, position.getZ() + 2);
            if (reservedPlacements.stream().anyMatch(reserved -> crystalBox.intersects(new AABB(reserved)))) continue;

            if (mc.level.getEntities((Entity) null, new AABB(position.offset(0, 1, 0)), entity -> true).stream().anyMatch(entity -> entity.isAlive() && !(entity instanceof ExperienceOrb) && !(entity instanceof EndCrystal))) continue;

            // A crystal WE just placed here needs a moment to actually land its hit before we
            // treat it as "in the way" and attack it ourselves (the tickCount grace period, keyed
            // off attackSpeed). Any OTHER crystal already sitting here isn't waiting on anything
            // and should count as an obstruction immediately -- otherwise calculatePlacements kept
            // skipping this position for the whole grace window every cycle, repeatedly searching
            // for (and often failing to find) somewhere else instead of clearing what's actually
            // blocking the best spot. Not PingBypass-specific -- this module and ServerAutoCrystal
            // both had the same bug independently.
            List<Entity> obstructingCrystals = mc.level.getEntities((Entity) null, new AABB(position.offset(0, 1, 0)), entity -> true).stream().filter(entity -> entity instanceof EndCrystal crystal
                    && (!placedCrystals.containsKey(crystal.blockPosition().below()) || crystal.tickCount >= (20 - attackSpeed.getValue().intValue()) + 15)).toList();

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

                float comparisonDamage = damage + (position.equals(stickyPosition) ? STICKY_EPSILON : 0.0f);
                if (comparisonDamage > optimalDamage) {
                    optimalPosition = position;
                    optimalPlayer = player;
                    optimalDamage = comparisonDamage;
                }
            }
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

        // Was switchBack(..., 0, previousSlot) -- a hardcoded 0 instead of the slot we actually
        // switched TO. switchBack keys SILENT_RESTORE off that argument, so it removed key 0
        // (never present) and left this switch's own entry in the map forever: the restore went to
        // the stale client previousSlot instead of the real server slot, AND hasActiveSilentSwitch()
        // stayed permanently true from the first weakness attack onward, which permanently disabled
        // KillAura's Normal-mode switch (its guard at KillAuraModule:166).
        if (switchedSlot != -1) {
            InventoryUtils.switchBack(antiWeakness.getValue(), switchedSlot, previousSlot);
        }

        attackedCrystals.put(crystal.getId(), System.currentTimeMillis());
        attackTimer.reset();
        totalAttacks++;
    }

    private void place(BlockPos position) {
        InteractionHand hand = mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        Direction direction = WorldUtils.getClosestDirection(position, true);

        eu.client.utils.minecraft.NetworkUtils.sendSequencedPacket(sequence ->
                new ServerboundUseItemOnPacket(hand, new BlockHitResult(WorldUtils.getHitVector(position, direction), direction, position, false), sequence));

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

    // Called synchronously every tick (onUpdateMovement, before the async scan is even submitted --
    // this is O(1)/O(4), no need to defer it off-thread). Watches SpeedMine's primary target and
    // pre-places a crystal on it once few enough ticks are left, then leaves it alone until either
    // the target changes/stops (reset) or the block actually breaks (onDestroyBlock -> detonate).
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
            // A different block than whatever we were last tracking (new target, or SpeedMine
            // moved on) -- start over. Deliberately doesn't touch a crystal already placed for the
            // OLD target; that one's now unrelated and left for the normal attack pipeline.
            mineIgnoreMinedPos = minePos;
            mineIgnorePlacedPos = null;
        }

        if (mineIgnorePlacedPos != null) return; // already down for this target, just waiting on it to break
        if (primary.getTicksRemaining() > mineIgnoreTicks.getValue().intValue()) return;

        mineIgnoreTryPlace(minePos);
    }

    // Deliberately self-contained instead of routing through calculatePlacements/placeCrystals'
    // (this.placeTarget) -- that field is also being overwritten by the async executor on its own
    // schedule, and shoving a synthetic candidate into it here would just race that overwrite.
    // Small enough to duplicate the handful of checks/switch-handling directly.
    private void mineIgnoreTryPlace(BlockPos minePos) {
        if (!placeTimer.hasTimeElapsed(1000.0f - placeSpeed.getValue().floatValue() * 50.0f)) return;

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(minePos.above()); // on top of the block itself, tried first
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
            // Same fix as the main placement path: check line of sight to the support block, not
            // the air cell above it -- a real player places by clicking the block, not the air.
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
            // 2026-08-15: was `!equalsIgnoreCase("None")` -- see the mineIgnore place site's own
            // note (same duplicate-target pattern, same fix, same reason: via 1.21.x exposes the
            // extra out-of-cadence packet, via 1.20.x doesn't).
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(candidate)));

            boolean switched = false;
            if (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
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

    // "khi obsidian đó vỡ lập tức break cục crystal đó đi" -- no timer/range/canSee gate on
    // purpose: the whole feature exists to land this hit the instant the support block is
    // confirmed gone (onDestroyBlock fires on SpeedMine's own LOCAL break prediction, not a
    // server round trip -- see DestroyBlockEvent's callsite), not to queue behind the normal
    // attack pipeline's own pacing.
    private void mineIgnoreDetonate() {
        BlockPos placedPos = mineIgnorePlacedPos;
        mineIgnoreMinedPos = null;
        mineIgnorePlacedPos = null;
        if (placedPos == null) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) continue;
            if (!crystal.blockPosition().below().equals(placedPos)) continue;

            if (rotate.getValue().equalsIgnoreCase("Normal")) EUClient.ROTATION_MANAGER.legacyRotate(calculateRotations(Vec3.atCenterOf(crystal.blockPosition())), EUClient.ROTATION_MANAGER.getLegacyModulePriority(this));
            // 2026-08-15: was `!equalsIgnoreCase("None")` -- same duplicate-target fix as the
            // other mineIgnore/place sites above.
            if (rotate.getValue().equalsIgnoreCase("Packet") || rotate.getValue().equalsIgnoreCase("Silent")) EUClient.ROTATION_MANAGER.wireRotate(rotate.getValue(), RotationUtils.getRotations(Vec3.atCenterOf(crystal.blockPosition())));

            attack(crystal);
            return;
        }
    }

    private List<Player> getPlayers() {
        if (EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
            return List.of(mc.player);
        }

        long now = System.currentTimeMillis();
        if (now - lastPlayerCacheTime < PLAYER_CACHE_DURATION) return cachedPlayers;

        List<Player> players = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if (!player.isAlive()) continue;
            if (mc.player.distanceToSqr(player) > Mth.square(enemyRange.getValue().doubleValue())) continue;
            if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;

            players.add(player);
        }

        cachedPlayers = players;
        lastPlayerCacheTime = now;
        return players;
    }

    // Narrows getPlayers()'s full in-range list down to the single best candidate for targetMode
    // (or leaves it alone for "All"/a 0-1 length list) -- called at both scan sites so the
    // O(positions) candidate loop only ever runs its inner player loop once per position instead of
    // once per player. Doesn't touch cachedPlayers itself: other code (SuicideModule branch, the
    // cache TTL) still wants the real full list, this is purely a per-call view of it.
    private List<Player> selectTargets(List<Player> players) {
        if (players.size() <= 1 || targetMode.getValue().equalsIgnoreCase("All")) return players;

        Player best = null;
        double bestScore = 0.0;

        for (Player player : players) {
            double score = switch (targetMode.getValue()) {
                case "Nearest" -> -mc.player.distanceToSqr(player);
                case "Farthest" -> mc.player.distanceToSqr(player);
                default -> -(player.getHealth() + player.getAbsorptionAmount()); // "Health"
            };

            if (best == null || score > bestScore) {
                best = player;
                bestScore = score;
            }
        }

        return List.of(best);
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
