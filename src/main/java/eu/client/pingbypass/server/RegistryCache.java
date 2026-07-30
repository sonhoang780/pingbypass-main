package eu.client.pingbypass.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.level.WorldDataConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Loads vanilla registries and tags at proxy startup, caches serialized packets.
 * Mirrors WorldLoader.load()'s registry-building steps synchronously, without a real
 * world/save, to avoid Fabric mixin interference.
 */
public class RegistryCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryCache.class);
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    private final List<Packet<?>> registryPackets = new ArrayList<>();
    private final List<KnownPack> knownPacks = new ArrayList<>();
    private LayeredRegistryAccess<RegistryLayer> registries;
    private RegistryAccess registryManager;
    private boolean loaded;
    private volatile boolean loadAttempted;

    public void load() {
        LOGGER.info("Loading vanilla registries for proxy...");
        long start = System.currentTimeMillis();

        try {
            PackRepository packRepository = ServerPacksSource.createVanillaTrustedRepository();
            MinecraftServer.configurePackRepository(packRepository, WorldDataConfiguration.DEFAULT, true, true);
            List<PackResources> packs = packRepository.openAllSelected();
            try (MultiPackResourceManager resourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
                LayeredRegistryAccess<RegistryLayer> initialLayers = RegistryLayer.createRegistryAccess();

                List<Registry.PendingTags<?>> staticTags =
                        TagLoader.loadTagsForExistingRegistries(resourceManager, initialLayers.getLayer(RegistryLayer.STATIC));

                RegistryAccess.Frozen worldgenLoadContext = initialLayers.getAccessForLoading(RegistryLayer.WORLDGEN);
                List<HolderLookup.RegistryLookup<?>> worldgenContextRegistries =
                        TagLoader.buildUpdatedLookups(worldgenLoadContext, staticTags);

                RegistryAccess.Frozen worldgenRegistries = RegistryDataLoader.load(
                        resourceManager, worldgenContextRegistries, RegistryDataLoader.WORLDGEN_REGISTRIES, SYNC_EXECUTOR).join();

                List<HolderLookup.RegistryLookup<?>> dimensionContextRegistries =
                        Stream.concat(worldgenContextRegistries.stream(), worldgenRegistries.listRegistries()).toList();

                RegistryAccess.Frozen dimensionRegistries = RegistryDataLoader.load(
                        resourceManager, dimensionContextRegistries, RegistryDataLoader.DIMENSION_REGISTRIES, SYNC_EXECUTOR).join();

                registries = initialLayers.replaceFrom(RegistryLayer.WORLDGEN, worldgenRegistries, dimensionRegistries);

                for (Registry.PendingTags<?> pending : staticTags) {
                    pending.apply();
                }

                registryManager = registries.compositeAccess();

                knownPacks.addAll(resourceManager.listPacks().flatMap(pack -> pack.knownPackInfo().stream()).toList());

                com.mojang.serialization.DynamicOps<Tag> ops = registries.compositeAccess().createSerializationContext(NbtOps.INSTANCE);
                RegistrySynchronization.packRegistries(
                        ops,
                        registries.getAccessFrom(RegistryLayer.WORLDGEN),
                        Set.of(),
                        (key, entries) -> registryPackets.add(new ClientboundRegistryDataPacket(key, entries)));

                registryPackets.add(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(registries)));

                loaded = true;
            }

            long elapsed = System.currentTimeMillis() - start;
            LOGGER.info("Loaded {} registry packets in {}ms", registryPackets.size(), elapsed);
        } catch (Exception e) {
            LOGGER.error("Failed to load vanilla registries", e);
        }
    }

    public boolean isLoaded() {
        if (!loaded && !loadAttempted) {
            loadAttempted = true;
            load();
        }
        return loaded;
    }

    public List<Packet<?>> getRegistryPackets() { return registryPackets; }
    public List<KnownPack> getKnownPacks() { return knownPacks; }
    public LayeredRegistryAccess<RegistryLayer> getRegistries() { return registries; }
    public RegistryAccess getRegistryManager() { return registryManager; }
}
