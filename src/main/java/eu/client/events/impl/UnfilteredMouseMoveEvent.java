package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;

@Getter @AllArgsConstructor
public class UnfilteredMouseMoveEvent extends Event {
    private final double deltaX;
    private final double deltaY;
}
