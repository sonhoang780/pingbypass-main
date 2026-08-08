package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.PacketSendEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.system.Timer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;

@RegisterModule(name = "FakeLag", description = "Chokes sent packets to look like you are lagging.", category = Module.Category.MOVEMENT)
public class FakeLagModule extends Module {
    public NumberSetting choke = new NumberSetting("Choke", "The delay to choke packets for.", 2, 1, 5);
    // Ported from Sydney-Legacy -- instead of choking continuously above the speed/fall-distance
    // threshold, only choke the exact moment you step OUT of a hole (e.g. right after AutoCrystal
    // finishes and you're about to move on), matching modules like HoleSnap that want a real
    // choke window specifically at that transition rather than blanket movement chokes.
    public BooleanSetting onlyOnHoleLeave = new BooleanSetting("OnlyOnHoleLeave", "Only choke packets when leaving a hole.", false);
    // One-shot burst instead of continuous chokes -- disables itself `value`ms after the current
    // choke window finishes and releases its buffered packets. 0 = off (stays on). Was a separate
    // Boolean + delay NumberSetting pair; folded into one NumberSetting so there's no redundant
    // "enable" toggle sitting next to its own delay value.
    public NumberSetting autoDisable = new NumberSetting("AutoDisable", "AutoDisable", "Delay (ms) after the choke window finishes before FakeLag disables itself. 0 = off.", new Setting.Visibility(), 0, 0, 1000, 100);
    public ModeSetting mode = new ModeSetting("RenderMode", "The rendering that will be applied to the target entity.", "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(mode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(mode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private final ArrayList<ServerboundMovePlayerPacket> packets = new ArrayList<>();
    private final Timer timer = new Timer();
    private final Timer safety = new Timer();
    private final Timer chokeTimer = new Timer();
    private final Timer autoDisableTimer = new Timer();
    private boolean pendingAutoDisable = false;
    private boolean sending = false;
    private Vec3 serverPosition = null;
    private boolean isChoking = false;
    private BlockPos currentPos = null;

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if(getNull()) return;

        if(event.getPacket() instanceof ClientboundPlayerPositionPacket posPacket) {
            serverPosition = new Vec3(posPacket.change().position().x(), posPacket.change().position().y(), posPacket.change().position().z());
            sendPackets();
            safety.reset();
        } else if(event.getPacket() instanceof ClientboundDisconnectPacket || event.getPacket() instanceof ClientboundSetHealthPacket packet && packet.getHealth() <= 0) {
            sendPackets();
            safety.reset();
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketSendEvent event) {
        if (getNull() || sending || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet) || !hasPositionChange(packet)) return;

        if (!shouldChoke()) {
            serverPosition = mc.player.position();
            return;
        }

        synchronized (packets) {
            event.setCancelled(true);
            packets.add(packet);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (getNull() || serverPosition == null || mode.getValue().equalsIgnoreCase("None")) return;

        AABB box = new AABB(
                serverPosition.x - mc.player.getBoundingBox().getXsize() / 2.0,
                serverPosition.y,
                serverPosition.z - mc.player.getBoundingBox().getZsize() / 2.0,
                serverPosition.x + mc.player.getBoundingBox().getXsize() / 2.0,
                serverPosition.y + mc.player.getBoundingBox().getYsize(),
                serverPosition.z + mc.player.getBoundingBox().getZsize() / 2.0);

        if (mode.getValue().equalsIgnoreCase("Fill") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(event.getMatrices(), box, fillColor.getColor());
        if (mode.getValue().equalsIgnoreCase("Outline") || mode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(event.getMatrices(), box, outlineColor.getColor());
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (getNull()) return;

        if (onlyOnHoleLeave.getValue()) {
            BlockPos prev = currentPos;
            currentPos = BlockPos.containing(mc.player.position());
            BlockPos prevPos = prev == null ? currentPos : prev;

            if (!currentPos.equals(prevPos)) {
                boolean currentInHole = HoleSnapModule.isInHole(currentPos);
                boolean prevInHole = HoleSnapModule.isInHole(prevPos);
                if (!currentInHole && prevInHole) {
                    isChoking = true;
                    chokeTimer.reset();
                }
            }

            if (isChoking && chokeTimer.hasTimeElapsed((int) (choke.getValue().floatValue() * 100))) {
                sendPackets();
                isChoking = false;
                if (autoDisable.getValue().intValue() > 0) startAutoDisable();
            }
        } else if (!packets.isEmpty() && timer.hasTimeElapsed((int) (choke.getValue().floatValue()*100))) {
            sendPackets();
            timer.reset();
            if (autoDisable.getValue().intValue() > 0) startAutoDisable();
        }

        if (pendingAutoDisable && autoDisableTimer.hasTimeElapsed(autoDisable.getValue().intValue())) {
            pendingAutoDisable = false;
            setToggled(false);
        }
    }

    private void startAutoDisable() {
        pendingAutoDisable = true;
        autoDisableTimer.reset();
    }

    @Override
    public void onEnable() {
        timer.reset();
        safety.reset();
        isChoking = false;
        // Was only armed once an actual choke window finished and released its packets -- if
        // nothing ever gets choked (standing still, nothing to buffer), that never happens and
        // AutoDisable silently never fires. Start the countdown the moment the module turns on
        // instead; a real choke release still re-arms it (chokeTimer/timer paths below), which
        // only matters if that happens to land AFTER this flat delay would've already fired.
        if (autoDisable.getValue().intValue() > 0) startAutoDisable();
        else pendingAutoDisable = false;
        currentPos = null;
        serverPosition = mc.player != null ? mc.player.position() : null;
    }

    @Override
    public void onDisable() {
        if(getNull()) return;
        sendPackets();
        isChoking = false;
        currentPos = null;
    }

    private void sendPackets() {
        synchronized (packets) {
            sending = true;
            for(ServerboundMovePlayerPacket packet : packets) {
                mc.player.connection.send(packet);
            }
            if (!packets.isEmpty()) serverPosition = mc.player.position();
            packets.clear();
            sending = false;
        }
    }

    private boolean shouldChoke() {
        return onlyOnHoleLeave.getValue() ? isChoking
                : (EntityUtils.getSpeed(mc.player, EntityUtils.SpeedUnit.KILOMETERS) >= 5 || mc.player.fallDistance > 0) && safety.hasTimeElapsed(1000);
    }

    private boolean hasPositionChange(ServerboundMovePlayerPacket packet) {
        return packet instanceof ServerboundMovePlayerPacket.Pos || packet instanceof ServerboundMovePlayerPacket.PosRot;
    }

    @Override
    public String getMetaData() {
        return (shouldChoke() ? ChatFormatting.GREEN : ChatFormatting.RED) + "Choke";
    }
}
