package eu.client.events.impl;

import eu.client.events.Event;
import eu.client.modules.Module;
import eu.client.utils.rotations.Rotation;
import lombok.Getter;
import lombok.Setter;

// Shoreline's ClientRotationEvent (net.shoreline.client.impl.rotation.util.ClientRotationEvent),
// ported verbatim (setYaw/setPitch cancel-on-write): posted once per tick by RotationManager with a
// snapshot of the real rotation, and every module that wants to fake this tick's rotation subscribes
// at a priority ordering (see RotationPriorities) and races to cancel it first -- see RotationManager
// class doc for the full mechanism and why we moved to this from the old time-decaying priority queue.
public class ClientRotationEvent extends Event {
    @Getter
    private final Rotation rotation;

    // NOT part of Shoreline's version -- Shoreline has no equivalent of Sprint Grim's
    // isGrimCompensating() (no module there needs to know "is MY fake the one currently live" for a
    // third mixin to key off of), so this is our own addition: a module that cares can tag itself
    // here alongside setYaw/setPitch, and RotationManager exposes whoever wins as getRotationOwner().
    @Getter @Setter
    private Module owner;

    public ClientRotationEvent(Rotation rotation) {
        this.rotation = rotation;
    }

    public void setYaw(float yaw) {
        setCancelled(true);
        rotation.setYaw(yaw);
    }

    public void setPitch(float pitch) {
        setCancelled(true);
        rotation.setPitch(pitch);
    }
}
