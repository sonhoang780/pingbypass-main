package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterModule(name = "ESP", description = "Renders a box ESP around entities that you have selected.", category = Module.Category.VISUALS)
public class ESPModule extends Module {
    public BooleanSetting players = new BooleanSetting("Players", "Renders the box ESP on player entities.", true);
    public BooleanSetting hostiles = new BooleanSetting("Hostiles", "Renders the box ESP on hostile entities.", true);
    public BooleanSetting animals = new BooleanSetting("Animals", "Renders the box ESP on animal entities.", true);
    public BooleanSetting ambient = new BooleanSetting("Ambient", "Renders the box ESP on ambient entities.", false);
    public BooleanSetting invisibles = new BooleanSetting("Invisibles", "Renders the box ESP on invisible entities.", true);
    public BooleanSetting items = new BooleanSetting("Items", "Renders the box ESP on item entities.", true);
    public BooleanSetting others = new BooleanSetting("Others", "Renders the box ESP on miscellaneous entities.", false);

    // Block target ESP -- naming matches InventoryCleanerModule's own Mode/Whitelist exactly, not
    // renamed (BlockMode/Blocklist etc were explicitly rejected).
    // Forgot this on the first pass -- Mode/Whitelist rendered unconditionally with no toggle to
    // show/hide them or turn the whole feature off, and the chunk scanner ran regardless of
    // whether the user wanted block ESP at all. Blocks gates both the settings' visibility AND the
    // scan/render (see onEnable/onBlockScanTick/renderBlockTargets below).
    // 2026-08-20 FIX (reported: "Block ESP chỉ nhận diện Whitelist, không dùng Blacklist và All").
    // BlackList/All dropped -- All alone was already flagged "expensive, not recommended" and
    // BlackList never made sense for a highlight-what-you-want feature. Mode setting removed
    // entirely, blockWhitelist is now always whitelist-only.
    // 2026-08-21 FIX (reported: "Block ESP nằm ở chỗ tách biệt với Players/Hostiles/...") --
    // settings render in declaration order, and this used to sit AFTER Mode/FillColor/
    // OutlineColor, visually separated from the rest of the entity-target toggles above. Moved up
    // next to Others so it reads as one contiguous "what to target" block in the GUI list.
    public BooleanSetting blocks = new BooleanSetting("Block", "Renders a box ESP on whitelisted blocks (portals, ores, ...).", false);
    public WhitelistSetting blockWhitelist = new WhitelistSetting("Whitelist", "Blocks to highlight.", new BooleanSetting.Visibility(blocks, true), WhitelistSetting.Type.BLOCKS);

    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the target entities.", "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());
    // blocks/blockWhitelist share mode/fillColor/outlineColor above with entity ESP -- nobody
    // asked for the block target to have its own separate palette, so it doesn't get one.

    private List<Entity> targetEntities = new ArrayList<>();

    // Chunk-key -> matched block positions, port of StashFinderModule's own pending/budget/
    // chunk-load pattern (no Range cap -- whole render distance, per request). Rescanned whenever
    // the chunk (re)loads or the whitelist/mode changes; each chunk section is palette-checked via
    // LevelChunkSection.maybeHas() first so an all-air or all-irrelevant section costs one check,
    // not 4096.
    private final Map<Long, List<BlockPos>> matchedBlocks = new HashMap<>();
    private final Map<Long, Integer> pendingChunks = new HashMap<>();
    private static final int SCAN_DELAY_TICKS = 5;
    private static final int MAX_CHUNK_EVALS_PER_TICK = 2;
    private static boolean chunkListenerRegistered = false;
    private java.util.function.Predicate<BlockState> blockPredicate = state -> false;
    private String lastWhitelistSignature = "";
    private boolean lastBlocksEnabled = false;

    public ESPModule() {
        registerChunkListener();
    }

    private void registerChunkListener() {
        if (chunkListenerRegistered) return;
        chunkListenerRegistered = true;
        ClientChunkEvents.CHUNK_LOAD.register((ClientLevel level, LevelChunk chunk) -> {
            ESPModule module = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(ESPModule.class) : null;
            if (module == null || !module.isToggled() || !module.blocks.getValue()) return;
            if (Minecraft.getInstance().level != level) return;
            module.queueChunk(chunk.getPos().x(), chunk.getPos().z());
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((ClientLevel level, LevelChunk chunk) -> {
            ESPModule module = EUClient.MODULE_MANAGER != null ? EUClient.MODULE_MANAGER.getModule(ESPModule.class) : null;
            if (module == null) return;
            long key = chunkKey(chunk.getPos().x(), chunk.getPos().z());
            module.matchedBlocks.remove(key);
            module.pendingChunks.remove(key);
        });
    }

    private static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    private void queueChunk(int chunkX, int chunkZ) {
        pendingChunks.putIfAbsent(chunkKey(chunkX, chunkZ), SCAN_DELAY_TICKS);
    }

    @Override
    public void onEnable() {
        matchedBlocks.clear();
        pendingChunks.clear();
        lastBlocksEnabled = blocks.getValue();
        if (!blocks.getValue()) return;
        rebuildPredicate(); // also does the bootstrap chunk-queue loop, no need to repeat it here
    }

    @Override
    public void onDisable() {
        matchedBlocks.clear();
        pendingChunks.clear();
        lastBlocksEnabled = false;
    }

    private String whitelistSignature() {
        return String.join(",", blockWhitelist.getWhitelistIds());
    }

    private void rebuildPredicate() {
        lastWhitelistSignature = whitelistSignature();
        blockPredicate = state -> !state.isAir() && blockWhitelist.isWhitelistContains(state.getBlock());
        // Whitelist changed underneath already-scanned chunks -- their cached matches are stale,
        // force a rescan of everything currently loaded instead of leaving old highlights stuck.
        matchedBlocks.clear();
        pendingChunks.clear();
        if (mc.player == null || mc.level == null) return;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int radius = mc.options.getEffectiveRenderDistance();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (mc.level.hasChunk(cx, cz)) queueChunk(cx, cz);
            }
        }
    }

    @SubscribeEvent
    public void onBlockScanTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!blocks.getValue()) {
            // Toggled off mid-session (module itself stays enabled) -- drop stale state so a later
            // re-enable's off->on edge below is detected, and stop rendering/holding onto old matches.
            if (lastBlocksEnabled) {
                lastBlocksEnabled = false;
                matchedBlocks.clear();
                pendingChunks.clear();
            }
            return;
        }
        // Block toggle flipped off->on mid-session (module itself was already enabled) -- onEnable()
        // already handled the off->on-via-module-toggle case, this catches the other trigger.
        if (!lastBlocksEnabled) {
            lastBlocksEnabled = true;
            rebuildPredicate();
            return;
        }
        if (!whitelistSignature().equals(lastWhitelistSignature)) {
            rebuildPredicate();
            return;
        }
        if (pendingChunks.isEmpty()) return;

        int budget = MAX_CHUNK_EVALS_PER_TICK;
        var it = pendingChunks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            if (budget == 0) {
                e.setValue(1);
                continue;
            }
            budget--;
            it.remove();
            long key = e.getKey();
            int chunkX = (int) key;
            int chunkZ = (int) (key >>> 32);
            evaluateChunk(chunkX, chunkZ, key);
        }
    }

    private void evaluateChunk(int chunkX, int chunkZ, long key) {
        if (mc.level == null || !mc.level.hasChunk(chunkX, chunkZ)) return;
        LevelChunk chunk = mc.level.getChunk(chunkX, chunkZ);
        List<BlockPos> matches = new ArrayList<>();

        LevelChunkSection[] sections = chunk.getSections();
        int minSection = mc.level.getMinSectionY();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(blockPredicate)) continue;

            int sectionY = (minSection + i) << 4;
            int baseX = chunkX << 4, baseZ = chunkZ << 4;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (blockPredicate.test(state)) matches.add(new BlockPos(baseX + x, sectionY + y, baseZ + z));
                    }
                }
            }
        }

        if (matches.isEmpty()) matchedBlocks.remove(key);
        else matchedBlocks.put(key, matches);
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.level == null) return;

        List<Entity> targetEntities = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!isValidEntity(entity)) continue;

            targetEntities.add(entity);
        }

        this.targetEntities = targetEntities;
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.level == null) return;

        // 2026-08-20 FIX (reported: "Block trong ESP không được phụ thuộc vào other"). The old
        // `if (targetEntities.isEmpty()) return;` sat BEFORE renderBlockTargets(), so with every
        // entity toggle (incl. Others) off/empty, block ESP silently stopped rendering too even
        // though `blocks` is its own independent toggle. Entity loop and block render are now two
        // separately-gated halves of this method.
        if (!targetEntities.isEmpty()) {
            for (Entity entity : targetEntities) {
                Vec3 pos = EntityUtils.getRenderPos(entity, event.getTickDelta());
                AABB box = new AABB(pos.x - entity.getBoundingBox().getXsize()/2, pos.y, pos.z - entity.getBoundingBox().getZsize()/2, pos.x + entity.getBoundingBox().getXsize()/2, pos.y + entity.getBoundingBox().getYsize(), pos.z + entity.getBoundingBox().getZsize()/2);

                if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
                if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
            }
        }

        renderBlockTargets(event);
    }

    // getShape (render/outline shape), not getCollisionShape -- end portal and fire have no
    // collision but do have a render shape; portal's is empty too (isEmpty() below), so it falls
    // back to a full cube. Lava/water's shape already reflects real fluid height, no special case
    // needed. toAabbs() (not bounds()) so a non-cube shape (e.g. anything with partial boxes)
    // renders as its real multi-box shape, not one bounding box.
    private void renderBlockTargets(RenderWorldEvent event) {
        if (mc.level == null || !blocks.getValue() || matchedBlocks.isEmpty()) return;
        if (mode.getValue().equalsIgnoreCase("None")) return;
        boolean fill = mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both");
        boolean outline = mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both");
        if (!fill && !outline) return;

        for (List<BlockPos> positions : matchedBlocks.values()) {
            for (BlockPos pos : positions) {
                BlockState state = mc.level.getBlockState(pos);
                if (!blockPredicate.test(state)) continue; // stale cache entry -- block changed since last scan

                VoxelShape shape = state.getShape(mc.level, pos);
                List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(pos)) : shape.toAabbs().stream().map(b -> b.move(pos)).toList();

                for (AABB box : boxes) {
                    if (fill) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
                    if (outline) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
                }
            }
        }
    }

    private boolean isValidEntity(Entity entity) {
        if (players.getValue() && entity.getType() == EntityTypes.PLAYER) return true;
        if (hostiles.getValue() && entity.getType().getCategory() == MobCategory.MONSTER) return true;
        if (animals.getValue() && (entity.getType().getCategory() == MobCategory.CREATURE || entity.getType().getCategory() == MobCategory.WATER_CREATURE || entity.getType().getCategory() == MobCategory.WATER_AMBIENT || entity.getType().getCategory() == MobCategory.UNDERGROUND_WATER_CREATURE || entity.getType().getCategory() == MobCategory.AXOLOTLS))
            return true;
        if (ambient.getValue() && entity.getType().getCategory() == MobCategory.AMBIENT) return true;
        if (invisibles.getValue() && entity.isInvisible()) return true;
        if (items.getValue() && (entity.getType() == EntityTypes.ITEM || entity.getType() == EntityTypes.EXPERIENCE_BOTTLE)) return true;
        return others.getValue();
    }
}
