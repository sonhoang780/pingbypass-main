package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.*;
import eu.client.mixins.accessors.PlayerMoveC2SPacketAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.InventoryUtils;
import eu.client.utils.minecraft.MovementUtils;
import eu.client.utils.minecraft.NetworkUtils;
import eu.client.utils.system.MathUtils;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.List;

@RegisterModule(name = "ElytraFly", description = "Allows you to fly using an elytra without fireworks.", category = Module.Category.MOVEMENT)
public class ElytraFlyModule extends Module {
    public ModeSetting mode = new ModeSetting("Mode", "The mode that will be used for elytra flying.", "Control", new String[]{"Packet", "Control", "ControlRocket"});

    public NumberSetting horizontal = new NumberSetting("Horizontal", "The speed at which you will be flying horizontally.", new ModeSetting.Visibility(mode, "Packet", "Control"), 2.0f, 0.1f, 10.0f);
    public NumberSetting vertical = new NumberSetting("Vertical", "The speed at which you will be flying vertically.", new ModeSetting.Visibility(mode, "Packet", "Control"), 1.0f, 0.1f, 10.0f);

    public BooleanSetting moveVertically = new BooleanSetting("MoveVertically", "Whether or not to allow for vertical movement.", new ModeSetting.Visibility(mode, "Packet", "Control"), true);

    public BooleanSetting infiniteDurability = new BooleanSetting("InfiniteDurability", "Prevents your elytra from having any durability used up.", new ModeSetting.Visibility(mode, "Packet"), false);
    public BooleanSetting stopOnGround = new BooleanSetting("StopOnGround", "Stops flying when you hit the ground.", new ModeSetting.Visibility(mode, "Packet"), true);
    public ModeSetting ncpStrict = new ModeSetting("NCPStrict", "Makes use of special bypasses for the NoCheatPlus anticheat.", new ModeSetting.Visibility(mode, "Packet"), "None", new String[]{"None", "Old", "New", "Motion"});

    // ---- ControlRocket: real elytra+firework flight, WASD steers direction, camera stays free.
    // Ported from example-addon-master's ControlRocket.java (Boze API) -- the mechanism that
    // module's own doc comment calls out as the critical part: rotate the player to the WASD
    // direction BEFORE the tick runs (its EventTick.Pre), fire the rocket after the movement
    // packet is on the wire (EventTick.Post), then restore the real camera rotation before the
    // next frame renders. Camera is never actually locked to the flight direction -- only the
    // ENTITY's rotation is, for the duration of the tick.
    //
    // 2026-08-14 FIX (reported: "WASD does nothing in ControlRocket"). The port did the Pre half on
    // UpdateMovementEvent, which this project posts at ClientPlayerEntityMixin's tick$AFTER -- i.e.
    // AFTER AbstractClientPlayer.tick() has already run aiStep -> travel() for this tick. So the
    // flight rotation only ever reached the outgoing movement packet; every piece of LOCAL physics
    // (vanilla's travelFallFlying, which converts velocity toward getLookAngle() every tick, and the
    // client-side FireworkRocketEntity.tick boost, which does the same off the attached player's
    // look vector) still ran at the untouched CAMERA rotation. Net effect exactly as reported: the
    // server was told to boost along WASD, the client kept flying where the camera pointed, and the
    // client is the one that owns the position -- so WASD appeared to do nothing at all. The
    // direction is now set on PlayerUpdateEvent (tick$BEFORE, ahead of super.tick(), the true analog
    // of EventTick.Pre) so local physics AND the packet use the same rotation, and restored in
    // UpdateMovementEvent.Post (still inside LocalPlayer.tick, before any frame renders).
    //
    // The keys are read from the raw bindings, exactly like the ported module's prepDirection().
    // mc.player.input.getMoveVector() is wrong here twice over: at tick$BEFORE it is still LAST
    // tick's vector (KeyboardInput.tick() runs inside super.tick(), see SprintModule's cachedMove
    // doc), and once refreshed it may have been re-mapped onto a spoofed yaw by
    // RotationManager.computeMoveFix. mc.options.keyX.isDown() is the same source KeyboardInput.tick()
    // itself reads, with neither problem.
    public NumberSetting crConserveDelay = new NumberSetting("ConserveDelay", "Ticks between rocket fires. 0 fires every tick a direction key is held.", new ModeSetting.Visibility(mode, "ControlRocket"), 0.0f, 0.0f, 60.0f);
    // GrimV3 -- the classic elytra durability-preservation swap, not an anticheat trick (the old
    // "dodges a durability-delta flag" framing was wrong and is gone). Verified against 26.1.2's own
    // LivingEntity bytecode:
    //   updateFallFlying(): server-side only -- if (!canGlide()) { setSharedFlag(7,false); return; }
    //                       else i = fallFlyTicks+1; if (i%10==0 && (i/10)%2==0) hurtAndBreak(1) on a
    //                       random glider slot  => one durability point every 20 gliding ticks.
    //   canGlide():         false unless an item with the glider component sits in its own equipment
    //                       slot -- for us, the elytra in the chest slot.
    //   LivingEntity.tick() tail: isFallFlying() ? fallFlyTicks++ : fallFlyTicks = 0.
    // So the elytra only has to be OUT of the chest slot at the instant the server runs
    // updateFallFlying ONCE inside every 20-tick window: canGlide() fails, the counter resets to 0,
    // and it never reaches the multiple of 20 that costs a point. Durability loss goes to zero.
    // That is why the cycle below is two-phase across two ticks and not the old both-clicks-in-one-
    // tick shape: the server handles a whole tick's incoming packets before it ticks the entity, so
    // taking the elytra off and putting it back on within one tick is invisible to updateFallFlying
    // and preserved exactly nothing.
    public BooleanSetting crGrimSwap = new BooleanSetting("GrimV3", "Cycles the elytra out of the chest slot for one tick at a time so it never loses durability.", new ModeSetting.Visibility(mode, "ControlRocket"), true);
    public NumberSetting crGrimDelay = new NumberSetting("GrimDelay", "Gliding ticks between each GrimV3 swap-out. Must stay under 19 for the durability reset to land.", new ModeSetting.Visibility(mode, "ControlRocket"), 10.0f, 1.0f, 18.0f);

    // Reference defaults, no longer settings -- see the trim note in onPlayerUpdate.
    private static final float CR_UP_PITCH = -0.4f * 90.0f;
    private static final float CR_DOWN_PITCH = 0.7f * 90.0f;
    private static final String CR_SWITCH = "Silent";

    private boolean crPendingFire;
    private float crSavedYaw, crSavedPitch;
    /** True from PlayerUpdateEvent's rotation swap until UpdateMovementEvent.Post restores it.
     *  Read by EntityMixin's getYRot/getXRot injections so render-side callers (arm renderer,
     *  Camera.update()) see the real camera rotation instead of the flight direction. */
    private boolean crCameraOverrideActive;
    public boolean isCrCameraOverrideActive() { return crCameraOverrideActive; }
    public float getCrSavedYaw() { return crSavedYaw; }
    public float getCrSavedPitch() { return crSavedPitch; }
    private int crRocketCooldown;
    private int crGrimTicks;
    /** Inventory slot the elytra is parked in for the current GrimV3 swap-out, or -1. */
    private int crParkedSlot = -1;
    /** What crParkedSlot actually looked like right before this swap-out (a spare chestplate, or
     *  empty) -- cached so the GUI/hotbar render mixins can keep SHOWING that instead of the real
     *  elytra that lands there for the cycle. See getGrimParkedDisplaced()'s own doc. */
    private net.minecraft.world.item.ItemStack crParkedDisplaced = net.minecraft.world.item.ItemStack.EMPTY;

    private float pitch;

    // Reference's onEnable() auto-equips the elytra from inventory if it isn't already worn --
    // this port never had that at all, so enabling ControlRocket while wearing a chestplate (or
    // anything else) simply did nothing: onPlayerUpdate's very first real check is
    // `getItemBySlot(CHEST).getItem() != ELYTRA -> return`, so every frame of the WASD/rotation
    // logic below it never ran. Reported as "mặc giáp vào mà Control Rocket không bay được, cũng
    // không điều khiển được hướng" -- both symptoms are this one early-return. The reference does
    // a clunky 2-tick click-handshake to equip; this project's swapEquipment() already does a full
    // one-shot 3-click swap synchronously, so onEnable can just call it directly, no staging needed.
    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) { setToggled(false); return; }
        if (!mode.getValue().equalsIgnoreCase("ControlRocket")) return;
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) return;

        int slot = InventoryUtils.find(Items.ELYTRA);
        if (slot == -1) {
            eu.client.EUClient.CHAT_MANAGER.tagged("No elytra found in your inventory.", getName());
            setToggled(false);
            return;
        }

        InventoryUtils.swapEquipment(slot, 6);
    }

    @SubscribeEvent
    public void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!mode.getValue().equalsIgnoreCase("Packet")) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(0.05F);

        if ((mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().inflate(-0.25, 0.0, -0.25).move(0.0, -0.3, 0.0)).iterator().hasNext() && stopOnGround.getValue()) || mc.player.getInventory().getItem(38).getItem() != Items.ELYTRA)
            return;

        mc.player.getAbilities().flying = true;
        mc.player.getAbilities().setFlyingSpeed(horizontal.getValue().floatValue() / 15.0f);
        event.setCancelled(true);

        if (Math.abs(event.getX()) < 0.05) event.setX(0);
        if (Math.abs(event.getZ()) < 0.05) event.setZ(0);

        event.setY(moveVertically.getValue() ? mc.options.keyJump.isDown() ? vertical.getValue().doubleValue() : mc.options.keyShift.isDown() ? -vertical.getValue().doubleValue() : 0 : 0);

        switch (ncpStrict.getValue().toLowerCase()) {
            case "old" -> event.setY(0.0002 - (mc.player.tickCount % 2 == 0 ? 0 : 0.000001) + MathUtils.random(0.0000009, 0));
            case "new" -> event.setY(-1.000088900582341E-12);
            case "motion" -> event.setY(-4.000355602329364E-12);
        }

        if (mc.player.horizontalCollision && (ncpStrict.getValue().equalsIgnoreCase("New") || ncpStrict.getValue().equalsIgnoreCase("Motion")) && mc.player.tickCount % 2 == 0) event.setY(-0.07840000152587923);

        if (infiniteDurability.getValue() || ncpStrict.getValue().equalsIgnoreCase("Motion")) {
            if (!MovementUtils.isMoving() && Math.abs(event.getX()) < 0.121) {
                float angleToRad = (float) Math.toRadians(4.5 * (mc.player.tickCount % 80));
                event.setX(Math.sin(angleToRad) * 0.12);
                event.setZ(Math.cos(angleToRad) * 0.12);
            }
        }
    }

    @SubscribeEvent
    public void onSendMovement(SendMovementEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!mode.getValue().equalsIgnoreCase("Packet")) return;

        if ((!mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().inflate(-0.25, 0.0, -0.25).move(0.0, -0.3, 0.0)).iterator().hasNext() || !stopOnGround.getValue()) && mc.player.getInventory().getItem(38).getItem() == Items.ELYTRA) {
            if (infiniteDurability.getValue() || !mc.player.isFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            if (mc.player.tickCount % 3 != 0 && ncpStrict.getValue().equalsIgnoreCase("Motion")) event.setCancelled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (mc.player == null || mc.level == null || !mc.player.isFallFlying()) return;

        if (mode.getValue().equalsIgnoreCase("Control")) {
            event.setCancelled(true);

            if (mc.player.input.getMoveVector().y == 0.0f && mc.player.input.getMoveVector().x == 0.0f) {
                mc.player.setDeltaMovement(new Vec3(0.0, mc.player.getDeltaMovement().y, 0.0));
            } else {
                pitch = 12;

                double cos = Math.cos(Math.toRadians(mc.player.getYRot() + 90.0f));
                double sin = Math.sin(Math.toRadians(mc.player.getYRot() + 90.0f));

                mc.player.setDeltaMovement(new Vec3(((mc.player.input.getMoveVector().y * horizontal.getValue().doubleValue() * cos) + (mc.player.input.getMoveVector().x * horizontal.getValue().doubleValue() * sin)), mc.player.getDeltaMovement().y, (mc.player.input.getMoveVector().y * horizontal.getValue().doubleValue() * sin) - (mc.player.input.getMoveVector().x * horizontal.getValue().doubleValue() * cos)));
            }

            mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z));

            if (moveVertically.getValue()) {
                if (mc.options.keyJump.isDown()) {
                    mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, vertical.getValue().doubleValue(), mc.player.getDeltaMovement().z));
                    pitch = -51;
                } else if (mc.options.keyShift.isDown()) {
                    mc.player.setDeltaMovement(new Vec3(mc.player.getDeltaMovement().x, -vertical.getValue().doubleValue(), mc.player.getDeltaMovement().z));
                    pitch = 0;
                }
            }
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketReceiveEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Same fall-flying-flag suppression for both modes that need it: "Packet" (never really
        // gliding at all) and ControlRocket+GrimV3 (genuinely gliding, but the server clears the
        // flag on every swap-out tick -- see grimSwapCycle).
        if (mode.getValue().equalsIgnoreCase("Packet") || (mode.getValue().equalsIgnoreCase("ControlRocket") && crGrimSwap.getValue())) {
            if (event.getPacket() instanceof ClientboundSetEntityDataPacket packet && packet.id() == mc.player.getId()) {
                List<SynchedEntityData.DataValue<?>> values = packet.packedItems();
                if (values.isEmpty()) return;

                for (SynchedEntityData.DataValue<?> value : values) {
                    if (value.value().toString().equals("FALL_FLYING") || (value.id() == 0 && (value.value().toString().equals("-120") || value.value().toString().equals("-128") || value.value().toString().equals("-126")))) {
                        event.setCancelled(true);
                    }
                }
            }

        }

        // The armor-equip sound GrimV3's two clicks per cycle would otherwise chirp twice a second.
        // Server-side origin only (LivingEntity.onEquipItem bails on the client), so dropping the
        // packet is the whole fix -- every vanilla equip sound is item.armor.equip_*.
        if (mode.getValue().equalsIgnoreCase("ControlRocket") && crGrimSwap.getValue() && mc.player.isFallFlying()
                && event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket sound
                && sound.getSound().value().location().getPath().startsWith("item.armor.equip")) {
            event.setCancelled(true);
        }

        if (mode.getValue().equalsIgnoreCase("Control")) {
            if (event.getPacket() instanceof ServerboundMovePlayerPacket packet && packet.hasRotation() && mc.player.isFallFlying()) {
                if (mode.getValue().equalsIgnoreCase("Control")) {
                    if (mc.options.keyLeft.isDown()) ((PlayerMoveC2SPacketAccessor) packet).setYaw(packet.getYRot(0.0f) - 90.0f);
                    if (mc.options.keyRight.isDown()) ((PlayerMoveC2SPacketAccessor) packet).setYaw(packet.getYRot(0.0f) + 90.0f);
                }

                ((PlayerMoveC2SPacketAccessor) packet).setPitch(pitch);
            }
        }
    }

    // ---- ControlRocket: Pre phase -- point the entity's rotation at the WASD direction before the
    // tick runs, so BOTH this tick's local elytra physics and the movement packet the server boosts
    // along use it. PlayerUpdateEvent is posted at ClientPlayerEntityMixin's tick$BEFORE, ahead of
    // super.tick() (aiStep -> travel()) and ahead of sendPosition(): the exact analog of the ported
    // module's EventTick.Pre. See the field block's 2026-08-14 note for what doing this on
    // UpdateMovementEvent instead cost.
    //
    // Settings trimmed here on purpose (2026-08-14): UpFraction/DownFraction were two sliders for
    // one feel knob and are now the reference's own defaults (CR_UP_PITCH/CR_DOWN_PITCH), and
    // RocketSwitch is pinned to "Silent" -- the only mode worth using mid-flight (Normal flashes the
    // hotbar, Alt* replays container clicks while we're already spending clicks on GrimV3). Put them
    // back as NumberSetting/ModeSetting if anyone actually wants the granularity.
    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (mc.player == null || mc.level == null) return;
        crPendingFire = false;
        if (!mode.getValue().equalsIgnoreCase("ControlRocket")) {
            // Mode switched mid-cycle -- put the parked elytra back on rather than leaving it in
            // the inventory (and the getItemBySlot lie stuck on) forever.
            if (crParkedSlot != -1) {
                InventoryUtils.swapEquipment(crParkedSlot, 6);
                crParkedSlot = -1;
                crParkedDisplaced = ItemStack.EMPTY;
            }
            return;
        }
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) return;

        // Auto-glide: start fall-flying the moment we're airborne and falling, same rising-edge
        // shape as the "Packet" mode above -- ControlRocket only steers an ALREADY-gliding player,
        // it doesn't take off on its own otherwise.
        if (!mc.player.isFallFlying() && !mc.player.onGround() && mc.player.getDeltaMovement().y < 0.0)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));

        grimSwapCycle();

        if (!mc.player.isFallFlying()) return;

        crSavedYaw = mc.player.getYRot();
        crSavedPitch = mc.player.getXRot();

        // Raw bindings, exactly like the ported module's prepDirection() -- dx/dz built from the
        // camera yaw, not from input.getMoveVector(). See the field block for why the move vector
        // is the wrong source at this hook point.
        double yawRad = Math.toRadians(crSavedYaw);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);
        double dx = 0.0, dz = 0.0;
        if (mc.options.keyUp.isDown())    { dx -= sinYaw; dz += cosYaw; }
        if (mc.options.keyDown.isDown())  { dx += sinYaw; dz -= cosYaw; }
        if (mc.options.keyLeft.isDown())  { dx += cosYaw; dz += sinYaw; }
        if (mc.options.keyRight.isDown()) { dx -= cosYaw; dz -= sinYaw; }

        double length = Math.sqrt(dx * dx + dz * dz);
        boolean hasHorizontal = length > 0.01;
        boolean space = mc.options.keyJump.isDown();
        boolean shift = mc.options.keyShift.isDown();

        float targetYaw = crSavedYaw;
        float targetPitch;
        if (hasHorizontal) {
            targetYaw = (float) Math.toDegrees(Math.atan2(-dx / length, dz / length));
            if (space) targetPitch = CR_UP_PITCH;
            else if (shift) targetPitch = CR_DOWN_PITCH;
            else {
                // Damp altitude oscillation: pitch slightly into the velocity error, same bias as
                // the ported module -- climbing pitches down to counter, falling pitches up.
                double vy = mc.player.getDeltaMovement().y;
                targetPitch = (float) Math.max(-20.0, Math.min(20.0, vy * 50.0 - 7.0));
            }
        } else if (space) {
            targetPitch = -90f;
        } else if (shift) {
            targetPitch = 90f;
        } else {
            return;
        }

        mc.player.setYRot(targetYaw);
        mc.player.setXRot(targetPitch);
        mc.player.setYBodyRot(targetYaw);

        // EntityMixin's getYRot*/getXRot* injections start returning crSavedYaw/crSavedPitch to
        // every render-side caller from here until the Post handler's finally block turns this
        // back off -- travel()/aiStep() read the raw field directly (unaffected), so local flight
        // physics still uses the real targetYaw/targetPitch just written above.
        crCameraOverrideActive = true;
        crPendingFire = true;
    }

    // ---- ControlRocket: Post phase -- the movement packet carrying targetYaw/targetPitch is
    // already on the wire by the time UpdateMovementEvent.Post fires (this project's equivalent
    // of EventTick.Post), so the rocket fires now with the server already holding that rotation,
    // then the real camera rotation is restored before the next frame renders.
    // MIN_VALUE so the camera restore below is the LAST thing to touch mc.player's rotation this
    // tick: RotationManager's own Post handler sits at the same priority and puts back the yaw it
    // snapshotted at the top of UpdateMovementEvent -- which, now that the flight rotation is set
    // back in PlayerUpdateEvent, is the FLIGHT yaw, not the camera's. Ties resolve in subscribe
    // order and RotationManager subscribes at client init, before any module, so it goes first.
    @SubscribeEvent(priority = Integer.MIN_VALUE)
    public void onUpdateMovementPost(UpdateMovementEvent.Post event) {
        if (mc.player == null || mc.level == null || !crPendingFire) return;
        crPendingFire = false;

        try {
            if (mc.player.onGround()) crRocketCooldown = 0;
            if (crRocketCooldown > 0) { crRocketCooldown--; return; }

            int previousSlot = mc.player.getInventory().getSelectedSlot();
            int slot = InventoryUtils.findHotbar(Items.FIREWORK_ROCKET);
            if (slot == -1) return;

            if (!InventoryUtils.switchSlot(CR_SWITCH, slot, previousSlot)) return;
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, crSavedYaw, crSavedPitch));
            InventoryUtils.switchBack(CR_SWITCH, slot, previousSlot);

            crRocketCooldown = mc.player.onGround() ? 0 : crConserveDelay.getValue().intValue();
        } finally {
            crCameraOverrideActive = false;
            mc.player.setYRot(crSavedYaw);
            mc.player.setXRot(crSavedPitch);
            // yRotO/xRotO get set to targetYaw/targetPitch by the entity tick that ran between
            // Pre and Post -- reset them too so getViewXRot's interpolation blends between
            // identical values next frame instead of snapping the first-person arms.
            mc.player.yRotO = crSavedYaw;
            mc.player.xRotO = crSavedPitch;
        }
    }

    // GrimV3, two-phase. Tick A: park the elytra in an empty inventory slot. The server handles that
    // click, then ticks us with an empty chest slot -> canGlide() false -> fallFlyTicks reset to 0
    // and the fall-flying flag cleared. Tick B (the very next one): put it straight back on and
    // re-send START_FALL_FLYING. The counter therefore restarts from 0 every crGrimDelay ticks and
    // never reaches the multiple of 20 that costs a durability point. See the setting's doc for the
    // 26.1.2 bytecode this is derived from.
    //
    // Neither half is visible or audible:
    //  * the server's "you stopped gliding" ClientboundSetEntityDataPacket is dropped in
    //    onPacketReceive below, so the client's own flag (and therefore its glide physics, glide
    //    pose and this module's steering) never blinks off;
    //  * the equip sound is server-side only (LivingEntity.onEquipItem returns immediately when
    //    level().isClientSide, so the only source is the ClientboundSoundPacket that
    //    playSeededSound broadcasts back to us) -- also dropped in onPacketReceive;
    //  * the one tick the chest slot is genuinely empty would otherwise pop the wings off the
    //    model, since WingsLayer/HumanoidArmorLayer draw from HumanoidRenderState.chestEquipment
    //    which is extracted from getItemBySlot(CHEST). getGrimHiddenElytra() below feeds
    //    LivingEntityMixin the parked stack for exactly that window, so every reader on the client
    //    -- render state included -- keeps seeing the elytra on.
    private void grimSwapCycle() {
        if (!crGrimSwap.getValue()) {
            crParkedSlot = -1;
            return;
        }

        if (crParkedSlot != -1) {
            InventoryUtils.swapEquipment(crParkedSlot, 6);
            crParkedSlot = -1;
            crParkedDisplaced = ItemStack.EMPTY;
            crGrimTicks = 0;
            if (!mc.player.onGround())
                mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return;
        }

        if (!mc.player.isFallFlying() || mc.player.onGround()) return;
        if (crGrimTicks++ < crGrimDelay.getValue().intValue()) return;

        // Was findEmptySlot -- parked the elytra next to nothing, so for that one tick the chest
        // slot's real content was "nothing" server-side. Reported: "GrimV3 phải swap giữa elytra
        // và giáp, sao lại tháo ra không". The classic version of this trick swaps with an actual
        // chestplate, not a bare inventory slot -- functionally identical for the durability reset
        // (canGlide() fails either way, see this method's own doc), but matches the real trick.
        // Falls back to an empty slot if the player is carrying no spare chestplate at all, rather
        // than silently skipping the whole cycle. Hotbar and main inventory are BOTH fair game
        // (bản gốc never restricted this to main inventory -- a spare chestplate parked in the
        // hotbar is exactly the real trick a human plays) -- see this method's 2026-08-15 note
        // below for why that no longer costs a visible flicker.
        //
        // 2026-08-15, SECOND PASS (reported: fix #1 below was "sai bản chất" -- wrong at the root.
        // The user's actual repro carries exactly one elytra + one spare chestplate, BOTH already
        // in the hotbar; there is no main-inventory slot to relocate the trick onto, so confining
        // parkSlot there (the first attempt) either does nothing for this setup or, worse, silently
        // fails the whole cycle when the main inventory has no empty slot either. The real bug was
        // never WHICH slot gets used -- swapEquipment's clicks are genuine, so whatever slot it
        // touches WILL hold the elytra for real, for one real tick, no matter where. The fix has to
        // be at the RENDER layer: keep letting the trick use the hotbar (bản gốc's own design,
        // reverted above), and instead lie to the two GUI read paths the same way
        // getGrimHiddenElytra()/LivingEntityMixin already lies to the 3rd-person model's armor
        // layer -- see GuiHotbarMixin (in-game hotbar) and HandledScreenMixin's slot-render redirect
        // (inventory/container screens), both driven off getGrimParkedSlot()/getGrimParkedDisplaced()
        // below. Neither of those two mixins is a movement/timing fix and neither touches
        // swapEquipment's clicks -- the swap is still real, only its two REAL render call sites
        // (Gui.extractItemHotbar's Inventory.getItem, AbstractContainerScreen.extractSlot's
        // Slot.getItem) are redirected to keep showing the pre-swap identity for exactly the one
        // slot the cycle is using.
        int chestplateSlot = findChestplateSlot();
        int parkSlot = chestplateSlot != -1 ? chestplateSlot
                : InventoryUtils.findEmptySlot(InventoryUtils.HOTBAR_START, InventoryUtils.INVENTORY_END);
        if (parkSlot == -1) return;

        crParkedDisplaced = mc.player.getInventory().getItem(parkSlot).copy();
        InventoryUtils.swapEquipment(parkSlot, 6);
        crParkedSlot = parkSlot;
    }

    // ponytail: first chestplate found, no protection ranking -- AutoArmor already owns "pick the
    // best piece" and can run alongside this.
    private int findChestplateSlot() {
        for (int i = InventoryUtils.HOTBAR_START; i <= InventoryUtils.INVENTORY_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.ELYTRA) continue;

            var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.slot() == EquipmentSlot.CHEST) return i;
        }
        return -1;
    }

    /** Inventory index (project convention: 0-8 hotbar-relative, 9+ raw container number) currently
     *  holding the parked elytra, or -1 if no swap-out is in flight. Read by GuiHotbarMixin --
     *  compares directly against Gui.extractItemHotbar's own loop index (same 0-8 convention). */
    public int getGrimParkedSlot() {
        return crParkedSlot;
    }

    /** What getGrimParkedSlot() looked like right before this swap-out -- a spare chestplate, or
     *  ItemStack.EMPTY. Read alongside getGrimParkedSlot() by GuiHotbarMixin/HandledScreenMixin so
     *  the hotbar and inventory/container screens keep showing that instead of the real elytra
     *  that lands there for the cycle (swapEquipment's clicks are genuine -- this only redirects
     *  the two RENDER read paths, never the actual container state). */
    public net.minecraft.world.item.ItemStack getGrimParkedDisplaced() {
        return crParkedDisplaced;
    }

    /** The parked elytra while a GrimV3 swap-out is in flight, else null. Read by LivingEntityMixin. */
    public net.minecraft.world.item.ItemStack getGrimHiddenElytra() {
        if (crParkedSlot == -1 || mc.player == null) return null;
        net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(crParkedSlot);
        return stack.getItem() == Items.ELYTRA ? stack : null;
    }

    @Override
    public void onDisable() {
        crPendingFire = false;
        crCameraOverrideActive = false;
        crGrimTicks = 0;
        // Never leave the elytra parked in the inventory because the module went off mid-cycle.
        if (crParkedSlot != -1 && mc.player != null) InventoryUtils.swapEquipment(crParkedSlot, 6);
        crParkedSlot = -1;
        crParkedDisplaced = ItemStack.EMPTY;
        if (mc.player == null) return;

        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().setFlyingSpeed(0.05F);
    }

    @Override
    public String getMetaData() {
        return mode.getValue();
    }
}