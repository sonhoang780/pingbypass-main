package eu.client.managers;

// Shoreline's net.shoreline.client.impl.modules.impl.Priorities, ported: @SubscribeEvent priority
// values for ClientRotationEvent subscribers, higher runs first (EventHandler.insert() -- see
// RotationManager class doc) and the first subscriber to cancel the event wins the tick's rotation.
// Relative order preserved 1:1 from the old RotationManager.PRIORITIES map (SelfFill > SpeedMine >
// AutoCrystal > KillAura > everything else at the default 0 -- Sprint, AutoBed, AutoWeb, MaceAura
// were never in that map either, so they keep losing arbitration exactly as before).
public final class RotationPriorities {
    public static final int SELF_FILL = 4;
    public static final int SPEED_MINE = 3;
    public static final int AUTO_CRYSTAL = 2;
    public static final int KILL_AURA = 1;

    private RotationPriorities() {
    }
}
