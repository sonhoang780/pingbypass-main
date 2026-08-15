package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.TickEvent;
import eu.client.mixins.accessors.Vec3dAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

@RegisterModule(name = "Velocity", description = "Modifies the amount of knockback that you receive.", category = Module.Category.MOVEMENT)
public class VelocityModule extends Module {
    // "Walls" ported 2026-08-15 from NamiDevelopment/nami-public's VelocityFeature (Mode.WALLS).
    // Unlike Normal (scales EVERY knockback packet, all the time -- an obvious tell to any
    // velocity-consistency check) it only touches knockback while isPhased() is true, i.e. the
    // player's own hitbox is actually overlapping solid block collision that tick (Surround/
    // SelfTrap/scaffold-clip situations) -- full, unmodified vanilla knockback the rest of the
    // time. Reducing KB only during the exact window a wall would otherwise shove you out/desync
    // you is far less suspicious than blanket-reducing everything.
    public ModeSetting mode = new ModeSetting("Mode", "The method that will be used to achieve the knockback modification.", "Normal", new String[]{"Normal", "Walls", "Cancel", "Grim"});
    public NumberSetting horizontal = new NumberSetting("Horizontal", "The amount of horizontal knockback that you will receive.", new ModeSetting.Visibility(mode, "Normal", "Walls"), 0, 0, 100);
    public NumberSetting vertical = new NumberSetting("Vertical", "The amount of vertical knockback that you will receive.", new ModeSetting.Visibility(mode, "Normal", "Walls"), 0, 0, 100);
    public BooleanSetting onlyOnGround = new BooleanSetting("OnlyOnGround", "Only reduces knockback while phased AND on the ground.", new ModeSetting.Visibility(mode, "Walls"), false);
    public BooleanSetting explosions = new BooleanSetting("Explosions", "Modifies knockback received from explosions.", true);
    public BooleanSetting pause = new BooleanSetting("Pause", "Pauses the velocity for a certain duration whenever you get rubberbanded.", new ModeSetting.Visibility(mode, "Cancel", "Grim"), true);

    public CategorySetting antiPushCategory = new CategorySetting("AntiPush", "Prevents certain things from pushing you.");
    public BooleanSetting antiPush = new BooleanSetting("AntiPush", "Entities", "Prevents other entities from pushing you.", new CategorySetting.Visibility(antiPushCategory), true);
    public BooleanSetting antiLiquidPush = new BooleanSetting("AntiLiquidPush", "Liquids", "Prevents liquids from pushing you.", new CategorySetting.Visibility(antiPushCategory), false);
    public BooleanSetting antiBlockPush = new BooleanSetting("AntiBlockPush", "Blocks", "Prevents you from being pushed outside of blocks.", new CategorySetting.Visibility(antiPushCategory), true);
    public BooleanSetting antiFishingRod = new BooleanSetting("AntiFishingRod", "FishingRods", "Prevents fishing rods from pushing you.", new CategorySetting.Visibility(antiPushCategory), false);

    private boolean cancel;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;
        if (!cancel) return;

        if (mode.getValue().equalsIgnoreCase("Grim") && (!pause.getValue() || EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(100L))) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), EUClient.ROTATION_MANAGER.getServerYaw(), EUClient.ROTATION_MANAGER.getServerPitch(), mc.player.onGround(), mc.player.horizontalCollision));
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, mc.player.isVisuallyCrawling() ? mc.player.blockPosition() : mc.player.blockPosition().above(), Direction.DOWN));
        }

        cancel = false;
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null) return;

        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket packet) {
            if (packet.id() != mc.player.getId()) return;

            switch (mode.getValue()) {
                case "Normal" -> scaleVelocity(event, packet);
                case "Walls" -> {
                    if (!isPhased(mc.player) || (onlyOnGround.getValue() && !mc.player.onGround())) return;
                    scaleVelocity(event, packet);
                }
                case "Cancel" -> {
                    if (pause.getValue() && !EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(100L)) return;

                    event.setCancelled(true);
                }
                case "Grim" -> {
                    if (pause.getValue() && !EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(100L)) return;

                    event.setCancelled(true);
                    cancel = true;
                }
            }
        }

        if (event.getPacket() instanceof ClientboundExplodePacket packet && explosions.getValue()) {
            switch (mode.getValue()) {
                case "Normal" -> scaleExplosion(packet);
                case "Walls" -> {
                    if (isPhased(mc.player)) scaleExplosion(packet);
                }
                case "Cancel" -> {
                    if (pause.getValue() && !EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(100L)) return;

                    event.setCancelled(true);
                }
                case "Grim" -> {
                    if (pause.getValue() && !EUClient.SERVER_MANAGER.getSetbackTimer().hasTimeElapsed(100L)) return;

                    event.setCancelled(true);
                    cancel = true;
                }
            }

            if (event.isCancelled()) {
                mc.executeBlocking(() -> {
                    Vec3 vec3d = packet.center();
                    mc.getSoundManager().play(new SimpleSoundInstance(packet.explosionSound().value(), SoundSource.BLOCKS, 4.0F, (1.0F + (mc.level.getRandom().nextFloat() - mc.level.getRandom().nextFloat()) * 0.2F) * 0.7F, mc.level.getRandom(), vec3d.x, vec3d.y, vec3d.z));
                    mc.level.addParticle(packet.explosionParticle(), vec3d.x, vec3d.y, vec3d.z, 1.0, 0.0, 0.0);
                });
            }
        }
    }

    private void scaleVelocity(PacketReceiveEvent event, ClientboundSetEntityMotionPacket packet) {
        Vec3 velocity = mc.player.getDeltaMovement();
        Vec3 target = packet.movement();

        double x = (target.x - velocity.x) * (horizontal.getValue().doubleValue() / 100.0) + velocity.x;
        double y = (target.y - velocity.y) * (vertical.getValue().doubleValue() / 100.0) + velocity.y;
        double z = (target.z - velocity.z) * (horizontal.getValue().doubleValue() / 100.0) + velocity.z;

        event.setCancelled(true);
        mc.player.lerpMotion(new Vec3(x, y, z));
    }

    private void scaleExplosion(ClientboundExplodePacket packet) {
        if (packet.playerKnockback().isPresent()) ((Vec3dAccessor) packet.playerKnockback().get()).setX((float) (packet.playerKnockback().get().x * (horizontal.getValue().doubleValue() / 100.0)));
        if (packet.playerKnockback().isPresent()) ((Vec3dAccessor) packet.playerKnockback().get()).setY((float) (packet.playerKnockback().get().y * (vertical.getValue().doubleValue() / 100.0)));
        if (packet.playerKnockback().isPresent()) ((Vec3dAccessor) packet.playerKnockback().get()).setZ((float) (packet.playerKnockback().get().z * (horizontal.getValue().doubleValue() / 100.0)));
    }

    // Ported from Nami's PlayerUtils.isPhased(): true iff the entity's own hitbox actually
    // overlaps solid block collision this tick (the player is clipped into a wall/scaffold, not
    // just standing next to one).
    private boolean isPhased(Entity entity) {
        if (entity == null || mc.level == null) return false;

        AABB box = entity.getBoundingBox();
        int minX = Mth.floor(box.minX), maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.ceil(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.ceil(box.maxZ);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
                    if (!shape.isEmpty() && shape.bounds().move(pos).intersects(box)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public String getMetaData() {
        if (mode.getValue().equalsIgnoreCase("Cancel")) return "0%, 0%";
        if (mode.getValue().equalsIgnoreCase("Grim")) return "Grim";
        return horizontal.getValue().intValue() + "%, " + vertical.getValue().intValue() + "%";
    }
}