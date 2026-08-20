package eu.client.modules.impl.miscellaneous;

import com.google.common.collect.Sets;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.LevelEvent; // Lớp chứa các hằng số mã sự kiện cấp độ

import java.util.Set;

@RegisterModule(name = "NoSoundLag", description = "Prevents lagging caused by a large amount of sounds being played.", category = Module.Category.MISCELLANEOUS)
public class NoSoundLagModule extends Module {
    public BooleanSetting armor = new BooleanSetting("Armor", "Prevents lagging caused by armor sounds.", true);
    public BooleanSetting withers = new BooleanSetting("Withers", "Prevents lagging caused by wither sounds.", true);
    public BooleanSetting ghasts = new BooleanSetting("Ghasts", "Prevents lagging caused by ghast sounds.", true);
    public BooleanSetting piston = new BooleanSetting("Piston", "Prevents lagging caused by piston sounds.", true);
    public BooleanSetting dispenser = new BooleanSetting("Dispenser", "Prevents lagging caused by dispenser sounds.", true);

    public static final Set<Holder<SoundEvent>> ARMOR_SOUNDS = Sets.newHashSet(SoundEvents.ARMOR_EQUIP_GENERIC, SoundEvents.ARMOR_EQUIP_ELYTRA, SoundEvents.ARMOR_EQUIP_DIAMOND, SoundEvents.ARMOR_EQUIP_IRON, SoundEvents.ARMOR_EQUIP_GOLD, SoundEvents.ARMOR_EQUIP_CHAIN, SoundEvents.ARMOR_EQUIP_LEATHER);
    public static final Set<SoundEvent> WITHER_SOUNDS = Sets.newHashSet(SoundEvents.WITHER_AMBIENT, SoundEvents.WITHER_DEATH, SoundEvents.WITHER_BREAK_BLOCK, SoundEvents.WITHER_HURT, SoundEvents.WITHER_SPAWN, SoundEvents.WITHER_SHOOT);
    public Set<SoundEvent> GHAST_SOUNDS = Sets.newHashSet(SoundEvents.GHAST_AMBIENT, SoundEvents.GHAST_DEATH, SoundEvents.GHAST_HURT, SoundEvents.GHAST_SCREAM, SoundEvents.GHAST_SHOOT, SoundEvents.GHAST_WARN);
    public static final Set<SoundEvent> PISTON_SOUNDS = Sets.newHashSet(SoundEvents.PISTON_EXTEND, SoundEvents.PISTON_CONTRACT);
    public static final Set<SoundEvent> DISPENSER_SOUNDS = Sets.newHashSet(SoundEvents.DISPENSER_DISPENSE, SoundEvents.DISPENSER_FAIL, SoundEvents.DISPENSER_LAUNCH);

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        // 1. Chặn âm thanh dạng SoundPacket thông thường (bao gồm cả Piston extend/contract)
        if (event.getPacket() instanceof ClientboundSoundPacket packet) {
            if ((armor.getValue() && ARMOR_SOUNDS.contains(packet.getSound())) ||
                (withers.getValue() && WITHER_SOUNDS.contains(packet.getSound().value())) ||
                (ghasts.getValue() && GHAST_SOUNDS.contains(packet.getSound().value())) ||
                (piston.getValue() && PISTON_SOUNDS.contains(packet.getSound().value())) ||
                (dispenser.getValue() && DISPENSER_SOUNDS.contains(packet.getSound().value()))) {
                event.setCancelled(true);
            }
        }

        // 2. Chặn âm thanh phát ra từ cơ chế khối Dispenser/Dropper (kích hoạt bằng mạch đỏ)
        if (event.getPacket() instanceof ClientboundLevelEventPacket levelEvent) {
            if (dispenser.getValue() && (
                levelEvent.getType() == LevelEvent.SOUND_DISPENSER_DISPENSE ||
                levelEvent.getType() == LevelEvent.SOUND_DISPENSER_FAIL ||
                levelEvent.getType() == LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH
            )) {
                event.setCancelled(true);
            }
        }
    }
}