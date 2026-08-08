package eu.client.commands.impl;

import com.mojang.authlib.GameProfile;
import eu.client.EUClient;
import eu.client.commands.Command;
import eu.client.commands.RegisterCommand;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

// 1:1 port of homovore's own FakePlayerCommand (dev.leonetic.features.commands.impl), not
// FakePlayerModule's single-instance version this used to delegate to -- deleted per request rather
// than kept as a fallback. Homovore's own List<RemotePlayer>/nextId bookkeeping, spawn/remove/pop/
// swim subcommands, and COPIED_SLOTS equipment copy are all reproduced verbatim; only the base-class
// glue changed (Command.execute(String[])/getSuggestions instead of homovore's own
// createArgumentBuilder/nullCheck/success/fail, since this project's CommandManager already adapts
// every Command's old execute(String[]) API onto Brigadier itself -- see CommandManager.register()).
@RegisterCommand(name = "fakeplayer", aliases = {"fp", "togglefp"}, description = "Spawns a client-side fake player to test on.", syntax = "[remove|pop|swim|<name>]")
public class FakePlayerCommand extends Command {
    private static final byte TOTEM_POP_EVENT_ID = 35;

    private static final EquipmentSlot[] COPIED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final List<RemotePlayer> spawned = new ArrayList<>();
    private int nextId = -13337;

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            spawn("FakePlayer");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "remove" -> removeAll();
            case "pop" -> popAll();
            case "swim" -> toggleSwim();
            default -> spawn(args[0]);
        }
    }

    @Override
    public List<String> getSuggestions(String[] args) {
        if (args.length == 0) return List.of("remove", "pop", "swim");
        return List.of();
    }

    private boolean nullCheck() {
        return mc.level == null || mc.player == null;
    }

    private void popAll() {
        if (nullCheck() || mc.getConnection() == null) {
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }

        int popped = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            // handleEntityEvent() plays the real pop particles/animation (vanilla's own handler),
            // but PopChamsModule doesn't hook that -- it reacts to PlayerPopEvent, which
            // WorldManager.onPacketReceive only posts off a REAL network-received
            // ClientboundEntityEventPacket (PacketReceiveEvent, a mixin on the actual receive
            // path). Calling handleEntityEvent() directly here never touches that path, so PopChams
            // never saw a fake player's pop. Post the event ourselves too.
            mc.getConnection().handleEntityEvent(new ClientboundEntityEventPacket(fake, TOTEM_POP_EVENT_ID));
            EUClient.EVENT_HANDLER.post(new eu.client.events.impl.PlayerPopEvent(fake, 1));
            popped++;
        }

        if (popped == 0) {
            EUClient.CHAT_MANAGER.warn("No fake players to pop. Spawn one first.");
            return;
        }
        EUClient.CHAT_MANAGER.tagged("Popped " + popped + " fake player(s).", "FakePlayer");
    }

    private void toggleSwim() {
        if (nullCheck()) {
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }

        boolean anyStanding = false;
        int found = 0;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            found++;
            if (fake.getPose() != Pose.SWIMMING) anyStanding = true;
        }
        if (found == 0) {
            EUClient.CHAT_MANAGER.warn("No fake players. Spawn one first.");
            return;
        }

        Pose pose = anyStanding ? Pose.SWIMMING : Pose.STANDING;
        for (RemotePlayer fake : spawned) {
            if (fake.level() != mc.level) continue;
            fake.setPose(pose);
            fake.refreshDimensions();
        }
        EUClient.CHAT_MANAGER.tagged(pose == Pose.SWIMMING ? "Fake player(s) now in swim pose." : "Fake player(s) now standing.", "FakePlayer");
    }

    private void spawn(String name) {
        if (nullCheck()) {
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }

        int despawned = despawnAll();

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        RemotePlayer fake = new RemotePlayer(mc.level, profile);
        fake.setId(nextId--);
        fake.copyPosition(mc.player);
        fake.setYHeadRot(mc.player.getYHeadRot());
        fake.setHealth(20f);
        // Was only ever copying position/rotation -- spawning while crawling/swimming/sneaking
        // still spawned a plain standing pose, since Pose drives hitbox dimensions/animation and
        // is a completely separate field from position. refreshDimensions() recomputes the
        // hitbox for the new pose (same call toggleSwim() already uses for the same reason).
        fake.setPose(mc.player.getPose());
        fake.refreshDimensions();
        for (EquipmentSlot slot : COPIED_SLOTS) {
            fake.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
        }
        mc.level.addEntity(fake);
        spawned.add(fake);

        EUClient.CHAT_MANAGER.tagged((despawned > 0 ? "Replaced fake player with " : "Spawned fake player ") + name, "FakePlayer");
    }

    private void removeAll() {
        if (nullCheck()) {
            spawned.clear();
            EUClient.CHAT_MANAGER.warn("You need to be in a world to do that.");
            return;
        }
        int removed = despawnAll();
        EUClient.CHAT_MANAGER.tagged(removed == 0 ? "No fake players to remove." : "Removed " + removed + " fake player(s).", "FakePlayer");
    }

    private int despawnAll() {
        int removed = 0;
        Iterator<RemotePlayer> it = spawned.iterator();
        while (it.hasNext()) {
            RemotePlayer fake = it.next();
            it.remove();
            if (fake.level() != mc.level) continue;
            mc.level.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
            removed++;
        }
        return removed;
    }
}
