package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.visuals.chestscan.ChestScanChain;
import eu.client.modules.impl.visuals.chestscan.ChestScanStore;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.awt.Color;
import java.util.*;

@RegisterModule(name = "ChestScan", description = "Highlights opened chests by contents (empty/partial/full), with optional hopper-chain inference.", category = Module.Category.VISUALS)
public class ChestScanModule extends Module {
    public NumberSetting scanRadius = new NumberSetting("ScanRadius", "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0);
    public BooleanSetting hopperChain = new BooleanSetting("HopperChain", "Smart mode to check chests linked to the bottom chest by hoppers.", false);
    public ModeSetting renderMode = new ModeSetting("Mode", "Render mode for chests.", "Both", new String[]{"Both", "Fill", "Outline"});

    public CategorySetting emptyCategory = new CategorySetting("Empty", "Color for empty chests.");
    public ColorSetting emptyFillColor = new ColorSetting("EmptyFillColor", "Fill", "Fill color for empty chests.", new CategorySetting.Visibility(emptyCategory), new ColorSetting.Color(new Color(0, 200, 0, 120), false, false));
    public ColorSetting emptyOutlineColor = new ColorSetting("EmptyOutlineColor", "Outline", "Outline color for empty chests.", new CategorySetting.Visibility(emptyCategory), new ColorSetting.Color(new Color(0, 200, 0, 255), false, false));

    public CategorySetting partialCategory = new CategorySetting("Partial", "Color for partially filled chests.");
    public ColorSetting partialFillColor = new ColorSetting("PartialFillColor", "Fill", "Fill color for partially filled chests.", new CategorySetting.Visibility(partialCategory), new ColorSetting.Color(new Color(230, 200, 0, 120), false, false));
    public ColorSetting partialOutlineColor = new ColorSetting("PartialOutlineColor", "Outline", "Outline color for partially filled chests.", new CategorySetting.Visibility(partialCategory), new ColorSetting.Color(new Color(230, 200, 0, 255), false, false));

    public CategorySetting fullCategory = new CategorySetting("Full", "Color for full chests.");
    public ColorSetting fullFillColor = new ColorSetting("FullFillColor", "Fill", "Fill color for full chests.", new CategorySetting.Visibility(fullCategory), new ColorSetting.Color(new Color(220, 0, 0, 120), false, false));
    public ColorSetting fullOutlineColor = new ColorSetting("FullOutlineColor", "Outline", "Outline color for full chests.", new CategorySetting.Visibility(fullCategory), new ColorSetting.Color(new Color(220, 0, 0, 255), false, false));

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

    private BlockPos lastLookedAtChestPos = null;
    private boolean wasChestMenuOpenLastTick = false;
    private BlockPos openChestPos = null;
    private ChestScanStore.ChestStatus lastSnapshotStatus = null;

    private int chainTicks = 0;
    private Set<BlockPos> lastInferredEmpty = Collections.emptySet();

    private final Map<BlockPos, Long> missingSinceMs = new HashMap<>();
    private static final long PRUNE_GRACE_MS = 1500;

    public ChestScanStore getStore() {
        return store;
    }

    @Override
    public void onEnable() {
        store.loadForWorld();
        lastWorldKey = ChestScanStore.currentWorldKey();
    }

    @Override
    public void onDisable() {
        if (openChestPos != null && lastSnapshotStatus != null) {
            finalizeChestState(mc, openChestPos, lastSnapshotStatus);
        }
        lastWorldKey = null;
        lastLookedAtChestPos = null;
        wasChestMenuOpenLastTick = false;
        openChestPos = null;
        lastSnapshotStatus = null;
        missingSinceMs.clear();
    }

    private void maybeReloadStoreForWorld() {
        String key = ChestScanStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
            missingSinceMs.clear();
        }
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();

        boolean chestMenuOpenNow = mc.player.containerMenu instanceof ChestMenu;

        if (!chestMenuOpenNow && mc.gui.screen() == null) {
            lastLookedAtChestPos = resolveLookedAtChestPos(mc);
        }

        if (chestMenuOpenNow && !wasChestMenuOpenLastTick) {
            openChestPos = lastLookedAtChestPos;
            lastSnapshotStatus = null;
        }

        if (chestMenuOpenNow) {
            ChestMenu menu = (ChestMenu) mc.player.containerMenu;
            lastSnapshotStatus = computeStatus(menu.getContainer());
        }

        if (!chestMenuOpenNow && wasChestMenuOpenLastTick) {
            finalizeChestState(mc, openChestPos, lastSnapshotStatus);
            openChestPos = null;
            lastSnapshotStatus = null;
        }

        wasChestMenuOpenLastTick = chestMenuOpenNow;

        tickChainRecompute(mc);
    }

    private void tickChainRecompute(Minecraft mc) {
        if (!hopperChain.getValue()) {
            lastInferredEmpty = Collections.emptySet();
            return;
        }
        chainTicks++;
        if (chainTicks < 20) return;
        chainTicks = 0;

        BlockPos center = mc.player.blockPosition();
        int radius = scanRadius.getValue().intValue();
        Set<BlockPos> tracked = new HashSet<>(store.positions());

        Map<BlockPos, BlockPos> edges = ChestScanChain.findEdges(mc.level, tracked, center, radius);
        Map<BlockPos, ChestScanStore.ChestStatus> real = new HashMap<>();
        for (BlockPos pos : tracked) {
            real.put(pos, store.get(pos));
        }
        lastInferredEmpty = ChestScanChain.inferEmpty(edges, real);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;

        double radius = scanRadius.getValue().doubleValue();
        double radiusSq = radius * radius;
        BlockPos center = mc.player.blockPosition();

        boolean doFill = renderMode.getValue().equalsIgnoreCase("Fill") || renderMode.getValue().equalsIgnoreCase("Both");
        boolean doOutline = renderMode.getValue().equalsIgnoreCase("Outline") || renderMode.getValue().equalsIgnoreCase("Both");

        Set<BlockPos> renderedPositions = new HashSet<>();

        for (BlockPos pos : new ArrayList<>(store.positions())) {
            if (center.distSqr(pos) > radiusSq) continue;
            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                long now = System.currentTimeMillis();
                Long since = missingSinceMs.putIfAbsent(pos, now);
                if (since != null && now - since >= PRUNE_GRACE_MS) {
                    store.remove(pos);
                    missingSinceMs.remove(pos);
                }
                continue;
            }
            missingSinceMs.remove(pos);

            if (renderedPositions.contains(pos)) continue;

            ChestScanStore.ChestStatus status = store.get(pos);
            if (status == null) continue;

            AABB box = getChestBox(pos, renderedPositions);
            Color fill = getFillColor(status);
            Color outline = getOutlineColor(status);

            if (doFill) Renderer3D.renderBox(event.getMatrices(), box, fill);
            if (doOutline) Renderer3D.renderBoxOutline(event.getMatrices(), box, outline);
        }

        if (hopperChain.getValue()) {
            Color fill = emptyFillColor.getColor();
            Color outline = emptyOutlineColor.getColor();
            for (BlockPos pos : lastInferredEmpty) {
                if (store.get(pos) != null) continue;
                if (center.distSqr(pos) > radiusSq) continue;
                if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                if (renderedPositions.contains(pos)) continue;

                BlockState state = mc.level.getBlockState(pos);
                if (!(state.getBlock() instanceof ChestBlock)) continue;

                AABB box = getChestBox(pos, renderedPositions);
                if (doFill) Renderer3D.renderBox(event.getMatrices(), box, fill);
                if (doOutline) Renderer3D.renderBoxOutline(event.getMatrices(), box, outline);
            }
        }
    }

    private AABB getChestBox(BlockPos pos, Set<BlockPos> renderedPositions) {
        renderedPositions.add(pos);
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction dir = state.getValue(ChestBlock.FACING);
            ChestType type = state.getValue(ChestBlock.TYPE);
            Direction connectedDir = (type == ChestType.LEFT) ? dir.getClockWise() : dir.getCounterClockWise();
            BlockPos other = pos.relative(connectedDir);
            BlockState otherState = mc.level.getBlockState(other);

            if (otherState.getBlock() instanceof ChestBlock && otherState.hasProperty(ChestBlock.TYPE) && otherState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                renderedPositions.add(other);
                return new AABB(
                        Math.min(pos.getX(), other.getX()),
                        Math.min(pos.getY(), other.getY()),
                        Math.min(pos.getZ(), other.getZ()),
                        Math.max(pos.getX(), other.getX()) + 1.0,
                        Math.max(pos.getY(), other.getY()) + 1.0,
                        Math.max(pos.getZ(), other.getZ()) + 1.0
                );
            }
        }
        return new AABB(pos);
    }

    private Color getFillColor(ChestScanStore.ChestStatus status) {
        return switch (status) {
            case EMPTY -> emptyFillColor.getColor();
            case PARTIAL -> partialFillColor.getColor();
            case FULL -> fullFillColor.getColor();
        };
    }

    private Color getOutlineColor(ChestScanStore.ChestStatus status) {
        return switch (status) {
            case EMPTY -> emptyOutlineColor.getColor();
            case PARTIAL -> partialOutlineColor.getColor();
            case FULL -> fullOutlineColor.getColor();
        };
    }

    private BlockPos resolveLookedAtChestPos(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        return (mc.level.getBlockState(pos).getBlock() instanceof ChestBlock) ? pos : null;
    }

    private ChestScanStore.ChestStatus computeStatus(Container container) {
        int total = container.getContainerSize();
        if (total == 0) return ChestScanStore.ChestStatus.EMPTY;
        int filled = 0;
        for (int i = 0; i < total; i++) {
            if (!container.getItem(i).isEmpty()) filled++;
        }
        if (filled == 0) return ChestScanStore.ChestStatus.EMPTY;
        return (filled == total) ? ChestScanStore.ChestStatus.FULL : ChestScanStore.ChestStatus.PARTIAL;
    }

    private void finalizeChestState(Minecraft mc, BlockPos pos, ChestScanStore.ChestStatus status) {
        if (pos == null || status == null) return;
        store.put(pos, status);
        if (mc == null || mc.level == null) return;
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction dir = state.getValue(ChestBlock.FACING);
            ChestType type = state.getValue(ChestBlock.TYPE);
            Direction connectedDir = (type == ChestType.LEFT) ? dir.getClockWise() : dir.getCounterClockWise();
            BlockPos other = pos.relative(connectedDir);
            if (mc.level.getBlockState(other).getBlock() instanceof ChestBlock) {
                store.put(other, status);
            }
        }
    }
}