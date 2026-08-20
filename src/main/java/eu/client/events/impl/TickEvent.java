package eu.client.events.impl;

import eu.client.events.Event;

public class TickEvent extends Event {
    /** Posted at Minecraft.tick()'s TAIL -- after level.tickEntities() (so after EVERY entity,
     *  including the local player AND any firework attached to it, has ticked) but still before
     *  MouseHandler.turnPlayer() and renderFrame(), both of which run later in runTick(). The only
     *  hook point where a per-tick rotation override can be held across the whole entity-tick phase
     *  and still be undone before the camera moves or a frame draws -- see ElytraFlyModule. */
    public static class Post extends Event {}
}
