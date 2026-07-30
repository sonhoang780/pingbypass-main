package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.world.entity.player.Player;

@AllArgsConstructor @Getter
public class TargetDeathEvent extends Event {
    private final Player player;
}
