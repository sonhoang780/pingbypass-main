package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

@Getter @AllArgsConstructor
public class PacketReceiveEvent extends Event {
    private final Packet<?> packet;
    private final Connection connection;

    /**
     * Legacy constructor for callers that don't provide a connection.
     */
    public PacketReceiveEvent(Packet<?> packet) {
        this(packet, null);
    }
}
