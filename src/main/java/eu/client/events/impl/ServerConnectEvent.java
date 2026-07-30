package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;

@AllArgsConstructor @Getter
public class ServerConnectEvent extends Event {
    private final ServerAddress address;
    private final ServerData info;
}
