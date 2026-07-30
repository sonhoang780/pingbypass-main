package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.world.entity.player.Player;

@Getter @AllArgsConstructor
public class PlayerDeathEvent extends Event {
    private final Player player;
}
