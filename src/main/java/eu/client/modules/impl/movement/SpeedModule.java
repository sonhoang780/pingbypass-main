package eu.client.modules.impl.movement;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.PlayerMoveEvent;
import eu.client.events.impl.TickEvent;
import eu.client.mixins.accessors.Vec3dAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.system.Timer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;

@RegisterModule(name = "Speed", description = "Makes it so that you move faster than normal.", category = Module.Category.MOVEMENT)
public class SpeedModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The method that will be used to increase your speed.", "Strafe", new String[]{"Vanilla", "Strafe", "StrafeStrict", "Grim"});

    public NumberSetting vanillaSpeed = new NumberSetting("VanillaSpeed", "Speed", "The speed that will be applied to your movement.", new ModeSetting.Visibility(mode, "Vanilla"), 10.0, 0.0, 20.0);
    public BooleanSetting vanillaOnGround = new BooleanSetting("VanillaOnGround", "OnGround", "Only applies the speed when you are on ground.", new ModeSetting.Visibility(mode, "Vanilla"), false);

    public BooleanSetting useTimer = new BooleanSetting("UseTimer", "Adds a timer multiplier when strafing.", new ModeSetting.Visibility(mode, "Strafe", "StrafeStrict"), false);
    public BooleanSetting timerBypass = new BooleanSetting("Bypass", "Allows you to use timer on certain servers.", new BooleanSetting.Visibility(useTimer, true), true);
    public NumberSetting bypassThreshold = new NumberSetting("Threshold", "The threshold value for the timer bypass.", new BooleanSetting.Visibility(timerBypass, true), 25, 15, 30);
    public NumberSetting timerMultiplier = new NumberSetting("TimerMultiplier", "Multiplier", "The timer multiplier that will be applied to the timer.", new BooleanSetting.Visibility(useTimer, true), 1.08f, 1.0f, 1.2f);
    public BooleanSetting speedInWater = new BooleanSetting("SpeedInWater", "Increases your speed while in water.", new ModeSetting.Visibility(mode, "Strafe", "StrafeStrict"), false);

    public BooleanSetting autoJump = new BooleanSetting("AutoJump", "Automatically jumps for you when on ground.", new ModeSetting.Visibility(mode, "Grim"), false);

    private double distance, speed, forward;
    private int stage, ticks;
    private boolean pressed = false;

    @Override
    public void onEnable() {
        stage = 1;
        ticks = 0;
    }

    @Override
    public void onDisable() {
        EUClient.WORLD_MANAGER.setTimerMultiplier(1.0f);
        if(pressed) mc.options.keyJump.setDown(false);
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (mode.getValue().equalsIgnoreCase("Strafe") || mode.getValue().equalsIgnoreCase("StrafeStrict")) {
            distance = Math.sqrt(Mth.square(mc.player.getX() - mc.player.xo) + Mth.square(mc.player.getZ() - mc.player.zo));
            EUClient.WORLD_MANAGER.setTimerMultiplier(isDrivingTimer() ? timerMultiplier.getValue().floatValue() : 1.0f);
        }

        if (mode.getValue().equalsIgnoreCase("Grim")) {
            if(autoJump.getValue() && MovementUtils.isMoving() && mc.player.onGround() && !pressed) {
                mc.options.keyJump.setDown(true);
                pressed = true;

                // Vanilla's own sprint-jump boost (Player.jumpFromGround()) ALSO fires this same
                // tick (isSprinting() is true, forced by Sprint's Grim mode) and pushes along
                // camera YAW, not actual movement direction. First attempt just ADDED a
                // correct-direction boost on top of it -- while facing your real direction of
                // travel (forward) that STACKED both boosts (reported: ~30km/h, too much); while
                // facing away from it (walking backward, Sprint Grim turning the model around)
                // vanilla's wrong-direction push partially CANCELLED the correct one instead
                // (reported: still stuck under 20km/h). Cancel vanilla's own boost out first, THEN
                // add the correct one -- exactly one clean 0.2 boost in the real movement
                // direction, regardless of which way the camera happens to be facing.
                if (mc.player.isSprinting()) {
                    float yRotRad = mc.player.getYRot() * (float) (Math.PI / 180.0);
                    double vanillaX = -Math.sin(yRotRad) * 0.2;
                    double vanillaZ = Math.cos(yRotRad) * 0.2;

                    Vector2d boost = MovementUtils.forward(0.2);
                    mc.player.setDeltaMovement(mc.player.getDeltaMovement().x - vanillaX + boost.x, mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z - vanillaZ + boost.y);
                }
            }

            if(!mc.player.onGround() && pressed) {
                mc.options.keyJump.setDown(false);
                pressed = false;
            }

            int collisions = 0;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity != null && entity != mc.player && entity instanceof LivingEntity && !(EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).getPlayer() == entity) && !(entity instanceof ArmorStand) && Mth.sqrt((float) mc.player.distanceToSqr(entity)) <= 1.5) {
                    collisions++;
                }
            }

            if (collisions > 0) {
                Vector2d vector2d = MovementUtils.forward(0.08 * collisions);
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x + vector2d.x, mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z + vector2d.y);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if (mode.getValue().equalsIgnoreCase("Strafe") || mode.getValue().equalsIgnoreCase("StrafeStrict")) {
            if ((EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(HoleSnapModule.class).hole != null)) return;

            if (mc.player.fallDistance >= 5.0f || mc.player.isShiftKeyDown() || mc.player.onClimbable() || mc.level.getBlockState(mc.player.blockPosition()).getBlock() == Blocks.COBWEB || mc.player.getAbilities().flying || (mc.player.isInFluid(net.minecraft.tags.FluidTags.WATER) && !speedInWater.getValue()))
                return;

            speed = MovementUtils.getPotionSpeed(MovementUtils.DEFAULT_SPEED) * (mc.player.input.getMoveVector().y <= 0 && forward > 0 ? 0.66 : 1);

            if(stage == 1 && MovementUtils.isMoving() && mc.player.verticalCollision) {
                ((Vec3dAccessor) mc.player.getDeltaMovement()).setY(MovementUtils.getPotionJump(0.3999999463558197));
                event.setMovement(new Vec3(event.getMovement().x, mc.player.getDeltaMovement().y, event.getMovement().z));
                speed *= 2.149;
                stage = 2;
            } else if(stage == 2) {
                speed = distance - (0.66 * (distance - MovementUtils.getPotionSpeed(MovementUtils.DEFAULT_SPEED)));
                stage = 3;
            } else {
                if (!mc.level.getEntityCollisions(mc.player, mc.player.getBoundingBox().move(0.0, mc.player.getDeltaMovement().y, 0.0)).isEmpty() || mc.player.verticalCollision)
                    stage = 1;

                speed = distance - distance / 159.0;
            }

            speed = Math.max(speed, MovementUtils.getPotionSpeed(MovementUtils.DEFAULT_SPEED));

            // Was `getMoveVector().y < 1` -- meant to tell "moving purely forward" (raw magnitude
            // 1, the higher speed cap) apart from "diagonal" (normalized to ~0.707, the lower
            // cap), which only works if getMoveVector() is actually guaranteed unit-length. Under
            // ViaFabricPlus's older-protocol input emulation that guarantee doesn't hold the same
            // way native connections give it (see MovementUtils.forward's comment) -- if it hands
            // back forward=1.0 even while ALSO strafing, this wrongly read as "pure forward",
            // picking the HIGHER cap during actual diagonal movement (reported: Strafe spiking to
            // ~44km/h on diagonal specifically under ViaFabricPlus). Raw key state instead --
            // "pure forward" means the forward key is held and NEITHER strafe key is, a direct
            // boolean check immune to whatever scaling the input source applies to the vector.
            boolean pureForward = mc.player.input.keyPresses.forward() && !mc.player.input.keyPresses.backward()
                    && !mc.player.input.keyPresses.left() && !mc.player.input.keyPresses.right();
            double ncp = MovementUtils.getPotionSpeed(mode.getValue().equalsIgnoreCase("StrafeStrict") || !pureForward ? 0.465 : 0.576);
            double bypass = MovementUtils.getPotionSpeed(mode.getValue().equalsIgnoreCase("StrafeStrict") || !pureForward ? 0.44 : 0.57);

            speed = Math.min(speed, ticks > 25 ? ncp : bypass);

            if (ticks++ > 50) ticks = 0;

            Vector2d velocity = MovementUtils.forward(speed);
            event.setMovement(new Vec3(velocity.x, event.getMovement().y, event.getMovement().z));
            event.setMovement(new Vec3(event.getMovement().x, event.getMovement().y, velocity.y));
            forward = mc.player.input.getMoveVector().y;

            event.setCancelled(true);
        }
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }

    /**
     * True only on ticks where this module is itself actively driving the world timer multiplier
     * (Strafe/StrafeStrict + UseTimer + actually strafing right now). TickShiftModule defers to
     * this instead of plain isToggled() -- gating on isToggled() alone blocked TickShift's own
     * boost whenever Speed was merely enabled, even in Vanilla/Grim mode or while Strafe wasn't
     * currently asserting a multiplier (standing still, sneaking, etc.), so the two never worked
     * together at all.
     */
    public boolean isDrivingTimer() {
        if (mc.player == null) return false;
        if (!(mode.getValue().equalsIgnoreCase("Strafe") || mode.getValue().equalsIgnoreCase("StrafeStrict"))) return false;
        if (!useTimer.getValue()) return false;

        boolean flag = MovementUtils.isMoving() && !mc.player.isShiftKeyDown() && !mc.player.isInFluid(net.minecraft.tags.FluidTags.WATER) && mc.player.fallDistance < 5.0f;
        return flag && (ticks > bypassThreshold.getValue().intValue() || !timerBypass.getValue());
    }
}
