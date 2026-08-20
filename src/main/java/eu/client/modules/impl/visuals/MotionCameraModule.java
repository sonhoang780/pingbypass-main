package eu.client.modules.impl.visuals;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.EntitySpawnEvent;
import eu.client.events.impl.PacketReceiveEvent;
import eu.client.events.impl.TickEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.animations.AnimateUtil;
import eu.client.utils.system.MathUtils;
import eu.client.utils.system.Timer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

@RegisterModule(name = "MotionCamera", description = "Smooths camera movement and adds smooth perspective transitions.", category = Module.Category.VISUALS)
public class MotionCameraModule extends Module {

    public BooleanSetting noFirstPerson = new BooleanSetting("NoFirstPerson", "Disables motion camera in first person.", true);
    // Pearl throws and rubberbands both whip the camera hard enough in 1st person that raw
    // (unsmoothed) motion reads as a jarring snap -- these two cases override NoFirstPerson for
    // a short window so the camera still smooths through them, same as it always does in 3rd
    // person, without giving up NoFirstPerson's normal 1st-person snappiness otherwise.
    public BooleanSetting pearlException = new BooleanSetting("PearlException", "Still smooths in 1st person for a moment after throwing an ender pearl (ignores NoFirstPerson).", new BooleanSetting.Visibility(noFirstPerson, true), true);
    public BooleanSetting rubberbandException = new BooleanSetting("RubberbandException", "Still smooths in 1st person for a moment after a rubberband/teleport correction (ignores NoFirstPerson).", new BooleanSetting.Visibility(noFirstPerson, true), true);
    public NumberSetting exceptionDuration = new NumberSetting("ExceptionDuration", "How long the pearl/rubberband exception window lasts, in ms.", new BooleanSetting.Visibility(noFirstPerson, true), 500, 100, 2000);
    public NumberSetting firstPersonSpeed = new NumberSetting("FirstPersonSpeed", "Movement smoothness speed in first person.", 0.6, 0.01, 1.0);
    public NumberSetting speed = new NumberSetting("Speed", "Movement smoothness speed in third person.", 0.3, 0.01, 1.0);

    public BooleanSetting smoothPerspective = new BooleanSetting("SmoothPerspective", "Smoothly transitions distance when switching between 1st and 3rd person.", true);
    public NumberSetting perspectiveSpeed = new NumberSetting("PerspectiveSpeed", "Speed of perspective switch transition.", 0.5, 0.05, 1.0);

    private double fakeX;
    private double fakeY;
    private double fakeZ;
    private double prevFakeX;
    private double prevFakeY;
    private double prevFakeZ;

    private double distance = 0.0;
    private double prevDistance = 0.0;

    private final Timer pearlTimer = new Timer();
    private final Timer rubberbandTimer = new Timer();

    @SubscribeEvent
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!pearlException.getValue() || mc.player == null) return;
        if (event.getEntity() instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof Player owner && owner == mc.player)
            pearlTimer.reset();
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!rubberbandException.getValue()) return;
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) rubberbandTimer.reset();
    }

    private boolean inExceptionWindow() {
        long window = exceptionDuration.getValue().longValue();
        return (pearlException.getValue() && !pearlTimer.hasTimeElapsed(window))
                || (rubberbandException.getValue() && !rubberbandTimer.hasTimeElapsed(window));
    }

    public boolean on() {
        if (mc.options == null) return false;
        return isToggled() && (!noFirstPerson.getValue() || !mc.options.getCameraType().isFirstPerson() || inExceptionWindow());
    }

    public boolean shouldBeDetached() {
        if (mc.options == null) return false;
        return isToggled() && smoothPerspective.getValue() && (distance > 0.01 || !mc.options.getCameraType().isFirstPerson());
    }

    @Override
    public void onEnable() {
        if (mc.player != null) {
            this.fakeX = mc.player.getX();
            this.fakeY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());
            this.fakeZ = mc.player.getZ();
            this.prevFakeX = this.fakeX;
            this.prevFakeY = this.fakeY;
            this.prevFakeZ = this.fakeZ;

            double targetDist = mc.options.getCameraType().isFirstPerson() ? 0.0 : getTargetPerspectiveDistance();
            this.distance = targetDist;
            this.prevDistance = targetDist;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.options == null) return;

        this.prevFakeX = this.fakeX;
        this.prevFakeY = this.fakeY;
        this.prevFakeZ = this.fakeZ;

        double currentSpeed = mc.options.getCameraType().isFirstPerson() ? firstPersonSpeed.getValue().doubleValue() : speed.getValue().doubleValue();
        double targetX = mc.player.getX();
        double targetY = mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose());
        double targetZ = mc.player.getZ();

        this.fakeX = AnimateUtil.animate(this.fakeX, targetX, currentSpeed);
        this.fakeY = AnimateUtil.animate(this.fakeY, targetY, currentSpeed);
        this.fakeZ = AnimateUtil.animate(this.fakeZ, targetZ, currentSpeed);

        // Update perspective distance. 3rd->1st snaps instantly (no lerp) -- only 1st->3rd (and
        // in-3rd-person distance changes, e.g. ViewClip) still smooth via AnimateUtil.
        this.prevDistance = this.distance;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        double targetDistance = firstPerson ? 0.0 : getTargetPerspectiveDistance();
        if (smoothPerspective.getValue() && !firstPerson) {
            this.distance = AnimateUtil.animate(this.distance, targetDistance, perspectiveSpeed.getValue().doubleValue());
        } else {
            this.distance = targetDistance;
        }
        // Snap prevDistance too on entering 1st person, otherwise getFakeDistance()'s own
        // partial-tick interpolate() (below) still blends prevDistance(old) -> distance(0) across
        // that one tick's render frames -- a residual 1-tick lerp even though `distance` itself
        // already jumped straight to 0 above.
        if (firstPerson) this.prevDistance = 0.0;
    }

    private double getTargetPerspectiveDistance() {
        ViewClipModule viewClip = EUClient.MODULE_MANAGER.getModule(ViewClipModule.class);
        if (viewClip != null && viewClip.isToggled() && viewClip.extend.getValue()) {
            return viewClip.distance.getValue().doubleValue();
        }
        return 4.0;
    }

    public double getFakeX() {
        float delta = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(true) : 1.0f;
        return MathUtils.interpolate(this.prevFakeX, this.fakeX, (double) delta);
    }

    public double getFakeY() {
        float delta = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(true) : 1.0f;
        return MathUtils.interpolate(this.prevFakeY, this.fakeY, (double) delta);
    }

    public double getFakeZ() {
        float delta = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(true) : 1.0f;
        return MathUtils.interpolate(this.prevFakeZ, this.fakeZ, (double) delta);
    }

    public double getDistance() {
        float delta = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(true) : 1.0f;
        return MathUtils.interpolate(this.prevDistance, this.distance, (double) delta);
    }
}
