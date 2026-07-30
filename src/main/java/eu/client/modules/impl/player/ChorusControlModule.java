package eu.client.modules.impl.player;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BindSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

//@RegisterModule(name = "ChorusControl", description = "Allows you to control the position that you will be teleported to when chorusing.", category = Module.Category.PLAYER)
public class ChorusControlModule extends Module {
    public BindSetting confirm = new BindSetting("Confirm", "The key that will be used to confirm the teleportation.", GLFW.GLFW_KEY_LEFT_SHIFT);

    public ModeSetting mode = new ModeSetting("Mode", "The rendering that will be applied to the target block.", "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private ClientboundPlayerPositionPacket packet;
    private boolean cancel = false;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (!cancel && mc.player.isUsingItem()) {
            ItemStack stack = mc.player.getItemInHand(mc.player.getUsedItemHand());
            if (stack.getItem() == Items.CHORUS_FRUIT && stack.getUseDuration(mc.player) - mc.player.getTicksUsingItem() <= 1) {
                cancel = true;
            }
        }
    }

    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (packet == null) return;

        if (event.getKey() != confirm.getValue()) return;

        packet.handle(mc.getConnection());

        packet = null;
        cancel = false;
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!cancel) return;

        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet && packet.hasPosition()) {
            event.setCancelled(true);
        }

        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            event.setCancelled(true);
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (event.getPacket() instanceof ClientboundPlayerPositionPacket packet && cancel) {
            event.setCancelled(true);
            this.packet = packet;
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (packet == null) return;

        Vec3 vec3d = new Vec3(packet.change().position().x(), packet.change().position().y(), packet.change().position().z());
        AABB box = EntityType.PLAYER.getDimensions().makeBoundingBox(vec3d);

        if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
        if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
    }

    @Override
    public void onDisable() {
        if (mc.getConnection() != null && packet != null) {
            packet.handle(mc.getConnection());
            packet = null;
            cancel = false;
        }
    }
}
