package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

@RegisterModule(name = "AntiVoid", description = "Prevents you from falling into the void.", category = Module.Category.MOVEMENT)
public class AntiVoidModule extends Module {
    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (falling()) {
            event.setCancelled(true);
            event.setMovement(new Vec3(event.getMovement().x, 0, event.getMovement().z));

            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));
        }
    }

    private boolean falling() {
        for (int i = (int) mc.player.getY(); i >= -64; i--) {
            if (!mc.level.isEmptyBlock(BlockPos.containing(mc.player.getX(), i, mc.player.getZ()))) {
                return false;
            }
        }

        return mc.player.fallDistance > 0;
    }
}
