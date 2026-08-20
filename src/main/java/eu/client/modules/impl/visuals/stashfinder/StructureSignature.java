package eu.client.modules.impl.visuals.stashfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Map;
import java.util.Set;

/**
 * Natural structure detection for StashFinder:
 * - Singleplayer: Exact ServerLevel structure-start data (ChunkAccess.getAllStarts() / getAllReferences()).
 * - Multiplayer: Advanced palette + Y-level + dimension + biome + container density heuristic.
 */
public class StructureSignature {

    public static boolean isNatural(Minecraft mc, LevelChunk chunk, Map<String, Integer> counts) {
        if (mc.level == null) return false;

        // 1. Player stash density override:
        // Even if a chunk resides in/near a natural structure (e.g. End City / Ancient City),
        // if it contains an excessive amount of storage containers, it is a player stash!
        int shulkers = counts.getOrDefault("Shulkers", 0);
        int chests = counts.getOrDefault("Chests", 0);
        int hoppers = counts.getOrDefault("Hoppers", 0);
        int barrels = counts.getOrDefault("Barrels", 0);
        int crafters = counts.getOrDefault("Crafters", 0);
        int enderChests = counts.getOrDefault("Ender Chests", 0);
        int donkeys = counts.getOrDefault("Donkey", 0);
        int chestBoats = counts.getOrDefault("Chest Boat", 0);

        if (shulkers >= 8 || chests >= 20 || hoppers >= 8 || barrels >= 16 || crafters >= 6 || enderChests >= 6 || donkeys >= 4 || chestBoats >= 3) {
            return false; // Definitely a player base/stash
        }

        // 2. Singleplayer exact query
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
            if (serverLevel != null) {
                ChunkPos pos = chunk.getPos();
                ChunkAccess serverChunk = serverLevel.getChunk(pos.x(), pos.z());
                if (serverChunk != null) {
                    for (Map.Entry<?, StructureStart> e : serverChunk.getAllStarts().entrySet()) {
                        if (e.getValue() != null && e.getValue().isValid()) return true;
                    }
                    for (var refs : serverChunk.getAllReferences().values()) {
                        if (refs != null && !refs.isEmpty()) return true;
                    }
                    return false;
                }
            }
        }

        // 3. Multiplayer heuristic
        return matchesMultiplayerHeuristic(mc, chunk);
    }

    private static boolean matchesMultiplayerHeuristic(Minecraft mc, LevelChunk chunk) {
        ResourceKey<Level> dim = mc.level != null ? mc.level.dimension() : Level.OVERWORLD;
        int[] counts = new int[Sig.values().length];
        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            int bottomY = chunk.getSectionYFromSectionIndex(i) << 4;

            for (int ly = 0; ly < 16; ly++) {
                int y = bottomY + ly;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Block block = section.getBlockState(lx, ly, lz).getBlock();
                        for (Sig sig : Sig.values()) {
                            if (sig.dimension != null && sig.dimension != dim) continue;
                            if (y >= sig.yMin && y <= sig.yMax && sig.blocks.contains(block)) {
                                counts[sig.ordinal()]++;
                            }
                        }
                    }
                }
            }
        }

        for (Sig sig : Sig.values()) {
            if (sig.dimension != null && sig.dimension != dim) continue;
            if (counts[sig.ordinal()] >= sig.threshold) {
                if (sig.biomeTag == null || chunkHasBiome(chunk, sig)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean chunkHasBiome(LevelChunk chunk, Sig sig) {
        if (sig.biomeTag == null) return true;
        ChunkPos pos = chunk.getPos();
        int midY = Math.clamp((sig.yMin + sig.yMax) / 2, chunk.getMinY(), chunk.getMaxY());
        int qy = midY >> 2;
        int minQX = pos.getMinBlockX() >> 2, minQZ = pos.getMinBlockZ() >> 2;
        int[][] samples = { {0, 0}, {3, 0}, {0, 3}, {3, 3}, {2, 2} };
        for (int[] s : samples) {
            Holder<Biome> b = chunk.getNoiseBiome(minQX + s[0], qy, minQZ + s[1]);
            if (b != null && b.is(sig.biomeTag)) return true;
        }
        return false;
    }

    private enum Sig {
        TRIAL_CHAMBERS(Set.of(Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.HEAVY_CORE, Blocks.CHISELED_COPPER, Blocks.COPPER_GRATE, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF, Blocks.POLISHED_TUFF), 2,
                BiomeTags.HAS_TRIAL_CHAMBERS, Level.OVERWORLD, -60, 20),

        ANCIENT_CITY(Set.of(Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_CATALYST, Blocks.SCULK_SHRIEKER, Blocks.SCULK_SENSOR, Blocks.SOUL_LANTERN, Blocks.SCULK), 8,
                BiomeTags.HAS_ANCIENT_CITY, Level.OVERWORLD, -64, -10),

        END_CITY(Set.of(Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.END_STONE_BRICKS, Blocks.END_ROD), 15,
                BiomeTags.HAS_END_CITY, Level.END, 40, 255),

        NETHER_FORTRESS(Set.of(Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS, Blocks.NETHER_WART), 25,
                BiomeTags.HAS_NETHER_FORTRESS, Level.NETHER, 30, 110),

        BASTION_REMNANT(Set.of(Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.CRYING_OBSIDIAN, Blocks.BLACKSTONE), 25,
                BiomeTags.HAS_BASTION_REMNANT, Level.NETHER, 0, 128),

        OCEAN_MONUMENT(Set.of(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN, Blocks.WET_SPONGE), 35,
                BiomeTags.HAS_OCEAN_MONUMENT, Level.OVERWORLD, 30, 70),

        WOODLAND_MANSION(Set.of(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.COBBLESTONE), 120,
                BiomeTags.HAS_WOODLAND_MANSION, Level.OVERWORLD, 50, 150),

        DESERT_PYRAMID(Set.of(Blocks.ORANGE_TERRACOTTA, Blocks.BLUE_TERRACOTTA, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE), 6,
                BiomeTags.HAS_DESERT_PYRAMID, Level.OVERWORLD, 40, 100),

        JUNGLE_TEMPLE(Set.of(Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_STONE_BRICKS, Blocks.TRIPWIRE_HOOK, Blocks.LEVER, Blocks.STICKY_PISTON), 8,
                BiomeTags.HAS_JUNGLE_TEMPLE, Level.OVERWORLD, 50, 100),

        VILLAGE(Set.of(Blocks.BELL, Blocks.COMPOSTER, Blocks.DIRT_PATH, Blocks.HAY_BLOCK, Blocks.FARMLAND), 8,
                null, Level.OVERWORLD, 50, 150),

        MINESHAFT(Set.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL, Blocks.COBWEB, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE, Blocks.SPRUCE_FENCE), 15,
                BiomeTags.HAS_MINESHAFT, Level.OVERWORLD, -60, 60),

        STRONGHOLD(Set.of(Blocks.END_PORTAL_FRAME, Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_COBBLESTONE, Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.IRON_BARS), 12,
                BiomeTags.HAS_STRONGHOLD, Level.OVERWORLD, -60, 40),

        SHIPWRECK(Set.of(Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_CHERRY_LOG), 15,
                BiomeTags.HAS_SHIPWRECK, Level.OVERWORLD, 20, 80),

        PILLAGER_OUTPOST(Set.of(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.TARGET), 30,
                null, Level.OVERWORLD, 50, 150),

        DUNGEON(Set.of(Blocks.SPAWNER, Blocks.MOSSY_COBBLESTONE), 6,
                null, Level.OVERWORLD, -60, 60),

        RUINED_PORTAL(Set.of(Blocks.CRYING_OBSIDIAN, Blocks.MAGMA_BLOCK, Blocks.GOLD_BLOCK), 4,
                BiomeTags.HAS_RUINED_PORTAL_STANDARD, null, 30, 120);

        final Set<Block> blocks;
        final int threshold;
        final TagKey<Biome> biomeTag;
        final ResourceKey<Level> dimension;
        final int yMin, yMax;

        Sig(Set<Block> blocks, int threshold, TagKey<Biome> biomeTag, ResourceKey<Level> dimension, int yMin, int yMax) {
            this.blocks = blocks;
            this.threshold = threshold;
            this.biomeTag = biomeTag;
            this.dimension = dimension;
            this.yMin = yMin;
            this.yMax = yMax;
        }
    }
}
