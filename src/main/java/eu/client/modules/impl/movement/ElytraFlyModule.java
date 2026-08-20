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
import eu.client.utils.minecraft.NetworkUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

@RegisterModule(name = "ElytraFly", description = "Allows you to fly using an elytra without fireworks.", category = Module.Category.MOVEMENT)
public class ElytraFlyModule extends Module {
    // "Packet" (fake-fly-flag movement override, no elytra/rocket physics involved at all) removed --
    // reported as unrelated to ControlRocket and dead weight sharing this module for no reason.
    public ModeSetting mode = new ModeSetting("Mode", "The mode that will be used for elytra flying.", "Control", new String[]{"Control", "ControlRocket"});

    public NumberSetting horizontal = new NumberSetting("Horizontal", "The speed at which you will be flying horizontally.", new ModeSetting.Visibility(mode, "Control"), 2.0f, 0.1f, 10.0f);
    public NumberSetting vertical = new NumberSetting("Vertical", "The speed at which you will be flying vertically.", new ModeSetting.Visibility(mode, "Control"), 1.0f, 0.1f, 10.0f);

    public BooleanSetting moveVertically = new BooleanSetting("MoveVertically", "Whether or not to allow for vertical movement.", new ModeSetting.Visibility(mode, "Control"), true);

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
    // Requested: a second steering scheme for ControlRocket. "Key" is the existing WASD-relative
    // behavior (steer only while a direction key is held, otherwise the flight direction just sits
    // wherever it was last set -- see the return in the no-input branch below). "Mouse" instead
    // always points the flight direction at wherever the camera is currently looking, every tick,
    // no key held required -- same feel as the old "Control" mode's direct-velocity flying, but
    // routed through real elytra+firework physics like the rest of ControlRocket.
    public ModeSetting crSteerMode = new ModeSetting("Control", "How ControlRocket steers: Key (WASD-relative, only while held) or Mouse (always follows the camera).", new ModeSetting.Visibility(mode, "ControlRocket"), "Key", new String[]{"Key", "Mouse"});
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
    // Default 10 -> 1 -> 15 -> back to 1. Min bound raised to 1 in an earlier pass on the (wrong)
    // assumption that 0 wasn't meaningful for a "ticks between cycles" count -- corrected: 0 is a
    // legitimate value here (put back the very next tick with no additional wait beyond the
    // mandatory two-phase park/unpark split), not a request to slow anything down.
    public NumberSetting crGrimDelay = new NumberSetting("GrimDelay", "Gliding ticks between each GrimV3 swap-out. Must stay under 19 for the durability reset to land.", new ModeSetting.Visibility(mode, "ControlRocket"), 1.0f, 0.0f, 18.0f);
    // Was hardwired to "GrimV3 && isFallFlying" (onPacketReceive's own doc) -- broadened to its own
    // toggle so the chirp gets muted for ANY elytra<->chestplate swap while flying under
    // ControlRocket, not only GrimV3's own cycling. Independent default true so existing GrimV3
    // users see no behavior change.
    public BooleanSetting muteElytra = new BooleanSetting("MuteElytra", "Mutes the armor-equip sound when ControlRocket swaps between elytra and chestplate.", new ModeSetting.Visibility(mode, "ControlRocket"), true);

    // Reference defaults, no longer settings -- see the trim note in onPlayerUpdate.
    private static final float CR_UP_PITCH = -0.4f * 90.0f;
    private static final float CR_DOWN_PITCH = 0.7f * 90.0f;
    private static final String CR_SWITCH = "Silent";

    private boolean crPendingFire;
    private float crSavedYaw, crSavedPitch;
    public float getCrSavedYaw() { return crSavedYaw; }
    public float getCrSavedPitch() { return crSavedPitch; }
    // 2026-08-19, THIRD PASS on the WASD-direction bug. Re-verified against example-addon-master's
    // own MixinEntity: the reference scopes its override to ONLY the tickDelta-taking overload
    // (getXRot(F)F/getYRot(F)F, render-interpolation), never the no-arg getYRot()/getXRot() vanilla
    // physics and sendPosition() actually call. Our port's EntityMixin used to match BOTH overloads
    // via a wildcard, which is what broke steering (see EntityMixin's own doc). A prior fix pass
    // deleted the override outright instead of narrowing it to match the reference -- restored here,
    // scoped correctly this time (see EntityMixin.controlRocket$overrideYaw/Pitch).
    private boolean crCameraOverrideActive;
    public boolean isCrCameraOverrideActive() { return crCameraOverrideActive; }
    // Real-time (ms), not a tick counter -- see onUpdateMovementPost's 2026-08-19 note.
    private long crLastRocketTime;
    private int crGrimTicks;
    // Set the instant grimSwapCycle()/sendGlideStart() actually performs a swap -- MuteElytra's
    // window checks THIS, not isFallFlying(). Reported: "MuteElytra không mute khi tôi onGround
    // (nếu bật GrimV3)" -- the LAST airborne swap's equip-sound packet is server-echoed and can
    // arrive after the landing tick already flipped isFallFlying() false, so the old
    // "mc.player.isFallFlying()" gate missed exactly the swap right at touchdown. 500ms is well
    // past any reasonable ping for that one echo, short enough not to swallow a real, unrelated
    // armor-equip sound the player triggers on their own moments later.
    private final eu.client.utils.system.Timer crLastSwapTimer = new eu.client.utils.system.Timer();
    /** True from the first START_FALL_FLYING this module actually sends until we touch ground again.
     *  Drives the client-side isFallFlying pin -- see LivingEntityMixin.euclient$pinControlRocketGlide. */
    private boolean crGliding;
    // Double-jump launch (reported: "khi tôi double jump thì phải cho tôi bay chứ?" -- with armor
    // worn, auto-glide only fires once you're ALREADY falling (vy < 0, below), same as vanilla real
    // elytra -- there's no jump-based launch at all, so the only way to start was to already be
    // falling off a ledge. Vanilla creative flight's own double-space-to-fly gesture is the more
    // familiar trigger here -- a second jump press within the window while airborne force-starts
    // gliding immediately (regardless of vy sign) with a small upward kick, instead of waiting to
    // fall first.
    private static final long CR_DOUBLE_JUMP_WINDOW_MS = 300L;
    private boolean crLastJumpDown;
    private long crLastJumpPressTime;
    /** A double-jump asked to take off; retried every airborne tick until gliding actually starts.
     *  See the launch block in onPlayerUpdate for why it can't just be sent on the press tick. */
    private boolean crLaunchPending;
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
    //
    // GrimV3 flips this: its rest state is CHESTPLATE-worn (elytra sitting in inventory the whole
    // time, see grimSwapCycle's own doc) -- if onEnable always forced the elytra ONTO the chest
    // slot regardless, turning GrimV3 on then enabling the module left the elytra worn and
    // grimSwapCycle()'s InventoryUtils.find(Items.ELYTRA) (which only searches inventory slots,
    // never the equipped one) permanently unable to find it -- GrimV3 silently never fires (flight
    // itself still works off the already-equipped elytra, so nothing LOOKED broken). Equip a
    // chestplate instead when GrimV3 is the active mode, matching what grimSwapCycle() expects.
    @Override
    public void onEnable() {
        if (!mode.getValue().equalsIgnoreCase("ControlRocket")) return;

        if (crGrimSwap.getValue()) {
            if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) return; // already chestplate/empty -- correct rest state
            int chestplateSlot = findChestplateSlot();
            if (chestplateSlot != -1) InventoryUtils.swapEquipment(chestplateSlot, 6);
            // No spare chestplate: leave the elytra worn rather than stripping armor to nothing --
            // grimSwapCycle() just won't find anything to flash until one becomes available.
            return;
        }

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

        // ControlRocket+GrimV3 USED to suppress the server's FALL_FLYING-clearing packet every
        // swap-out tick, hiding the real (server-confirmed) non-gliding tick behind a frozen
        // "Gliding" pose the whole time. Reported: "khi bay tôi vẫn thấy Gliding chứ không như đang
        // sprint+walk như bay ở sáng tạo" -- the swap genuinely does clear FALL_FLYING server-side
        // for that one tick (that's the whole mechanism the durability reset relies on), so the
        // suppression was removed: real walk/sprint pose for that one tick, gliding pose the rest
        // of the time, same as what's actually happening server-side instead of a client-only
        // illusion (see getGrimHiddenElytra()'s own doc for the matching render-side change).

        // The armor-equip sound any elytra<->chestplate swap triggers would otherwise chirp every
        // cycle. Server-side origin only (LivingEntity.onEquipItem bails on the client), so
        // dropping the packet is the whole fix -- every vanilla equip sound is item.armor.equip_*.
        // No longer gated on crGrimSwap specifically (was GrimV3-only) -- MuteElytra covers any
        // swap while flying under ControlRocket, GrimV3 or not.
        //
        // Gate is crLastSwapTimer (see its own doc), not mc.player.isFallFlying() -- the LAST
        // airborne swap's echoed sound packet can arrive after the landing tick already flipped
        // isFallFlying() false, and that isFallFlying()-gated version missed exactly that one.
        if (mode.getValue().equalsIgnoreCase("ControlRocket") && muteElytra.getValue() && !crLastSwapTimer.hasTimeElapsed(500)
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
        // GrimV3's REST state is chestplate-worn, not elytra (see grimSwapCycle's own doc) -- the
        // old hard gate here ("chest must be elytra or bail") used to fire on EVERY tick while
        // GrimV3 was on, since chest is basically never elytra except the single synchronous
        // instant inside grimSwapCycle() itself, which sits AFTER this check -- meaning the gate
        // permanently blocked grimSwapCycle() from ever running at all. That's the second half of
        // "mặc giáp thì ControlRocket không hoạt động": self-heal only applies (equip + resume
        // gliding) when GrimV3 is OFF, matching the "elytra always worn" model that path actually
        // implements; GrimV3 owns its own equipment state and must reach grimSwapCycle() every tick
        // regardless of what's currently on the chest slot.
        if (!crGrimSwap.getValue() && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            int elytraSlot = InventoryUtils.find(Items.ELYTRA);
            if (elytraSlot != -1) {
                InventoryUtils.swapEquipment(elytraSlot, 6);
                // Was equip-only -- re-equipping the elytra alone doesn't resume gliding on its
                // own; the server already cleared isFallFlying the instant canGlide() failed
                // (elytra left the chest slot), and nothing else here re-requests it. Reported:
                // "tôi đang bay mà swap sang giáp cũng không bay nữa" -- expected the swap to be
                // unconditional/transparent (equip whatever, keep flying), not a one-shot
                // recovery of the equipment alone. Immediately re-send the START_FALL_FLYING
                // action if airborne, same call self-heal is already trusted to make elsewhere.
                if (!mc.player.onGround())
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            return;
        }

        // Double-jump launch -- see the field block's own doc.
        //
        // 2026-08-19 FIX (reported: "double jump khi đang mặc giáp không làm gì cả -- không swap
        // sang elytra, không cất cánh"). The press tick used to fire START_FALL_FLYING itself, and
        // the server threw it away every single time, for TWO independent reasons -- re-checked
        // against .mcref's Player#tryToStartFallFlying, which needs
        // `!onGround() && !isFallFlying() && !isInWater() && canGlide()`:
        //   * onGround -- the packet goes out at tick$BEFORE, ahead of this tick's own
        //     sendPosition(), so the newest onGround the server has is still the PREVIOUS tick's,
        //     i.e. true, on the very tick the second press happens (an earlier pass deleted a local
        //     !onGround() guard here believing the server had no ground requirement at all -- it
        //     does, this is just the client's own copy of it, and deleting it changed nothing
        //     because the server kept rejecting anyway);
        //   * canGlide() -- false whenever the chest slot isn't an elytra, which under GrimV3 is
        //     the normal REST state (chestplate worn, elytra in the inventory -- see
        //     grimSwapCycle's own doc), so with GrimV3 on, wearing armor, it could never pass.
        // Both are handled by arming a launch instead of sending one: the send is retried on the
        // following ticks, once airborne (the server now has onGround=false), through
        // sendGlideStart(), which flashes the elytra on around the packet exactly like
        // grimSwapCycle -- so canGlide() holds for the one packet that needs it.
        boolean jumpDown = mc.options.keyJump.isDown();
        if (jumpDown && !crLastJumpDown) {
            long now = System.currentTimeMillis();
            if (now - crLastJumpPressTime <= CR_DOUBLE_JUMP_WINDOW_MS && !mc.player.isFallFlying()) {
                crLaunchPending = true;
                Vec3 kicked = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(kicked.x, Math.max(kicked.y, 0.45), kicked.z);
            }
            crLastJumpPressTime = now;
        }
        crLastJumpDown = jumpDown;

        // Auto-glide: start fall-flying the moment we're airborne and falling -- plus the armed
        // double-jump launch above, which doesn't wait to be falling first.
        // Landing ends the glide pin AND clears the rocket timer -- the old `if (onGround())
        // crRocketCooldown = 0` lived inside onUpdateMovementPost, which is unreachable while
        // grounded (it needs crPendingFire, which needs isFallFlying), so it never once ran.
        if (mc.player.onGround()) {
            crGliding = false;
            crLastRocketTime = 0L;
        }

        if (mc.player.isFallFlying() || mc.player.onGround()) crLaunchPending = false;
        else if (crLaunchPending || mc.player.getDeltaMovement().y < 0.0) sendGlideStart();

        grimSwapCycle();

        if (!mc.player.isFallFlying()) return;

        crSavedYaw = mc.player.getYRot();
        crSavedPitch = mc.player.getXRot();

        // Third-person-front (F5 x2, "mirrored") renders from IN FRONT of the player looking back
        // at them -- vanilla's Camera.setPosition does this at (entity.yRot+180, -entity.xRot)
        // whenever CameraType.isMirrored(). WASD here was built straight off the raw player yaw,
        // which only matches what's actually on screen in first-person/third-back; in mirrored view
        // it's 180 off, so pressing a direction key flew AWAY from what was visually being looked
        // at -- verified against the reference's own fix for this exact symptom (github.com/
        // sonhoang780/boze-addon commit 60ee248, "ControlRocket: WASD direction ignored
        // third-person-front (mirrored)"). Only the movement-basis yaw needs the correction --
        // crSavedYaw/Pitch (restored in Post, read by EntityMixin's tickDelta-overload override)
        // stay the real raw player rotation, since vanilla's own mirrored-camera math already
        // derives the correct render angle from that raw value on its own.
        float moveYaw = crSavedYaw;
        if (mc.options.getCameraType().isMirrored()) moveYaw += 180f;

        float targetYaw = crSavedYaw;
        float targetPitch;
        if (crSteerMode.getValue().equalsIgnoreCase("Mouse")) {
            // Always follow the camera, every tick, no key required -- see the setting's own doc.
            // moveYaw already carries the mirrored-camera correction; pitch never needed one (the
            // WASD-mode fix only touched the movement-basis YAW, see that fix's own comment).
            targetYaw = moveYaw;
            targetPitch = crSavedPitch;
        } else {
            // Raw bindings, exactly like the ported module's prepDirection() -- dx/dz built from
            // the camera yaw, not from input.getMoveVector(). See the field block for why the move
            // vector is the wrong source at this hook point.
            double yawRad = Math.toRadians(moveYaw);
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

            if (hasHorizontal) {
                targetYaw = (float) Math.toDegrees(Math.atan2(-dx / length, dz / length));
                if (space) targetPitch = CR_UP_PITCH;
                else if (shift) targetPitch = CR_DOWN_PITCH;
                else {
                    // Damp altitude oscillation: pitch slightly into the velocity error, same bias
                    // as the ported module -- climbing pitches down to counter, falling pitches up.
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
        }

        mc.player.setYRot(targetYaw);
        mc.player.setXRot(targetPitch);
        mc.player.setYBodyRot(targetYaw);

        crCameraOverrideActive = true;
        crPendingFire = true;
    }

    // ---- ControlRocket: Post phase -- fire the rocket (the movement packet carrying
    // targetYaw/targetPitch went out during LocalPlayer.tick, well before this), then restore the
    // real camera rotation.
    //
    // 2026-08-19 FIX (reported: "WASD steers for ~1 tick then snaps back to the camera direction,
    // repeating, even with the key HELD"). This used to run on UpdateMovementEvent.Post, which
    // ClientPlayerEntityMixin posts INSIDE LocalPlayer.tick() -- i.e. the flight rotation only ever
    // existed for the duration of the player's OWN tick. But the thing that actually flies an
    // elytra with rockets is not travel(): it's FireworkRocketEntity.tick(), which every tick does
    //     attachedToEntity.setDeltaMovement(v.add(look*0.1 + (look*1.5 - v)*0.5))
    // -- a 50%-per-tick pull of the PLAYER's velocity onto attachedToEntity.getLookAngle()
    // (verified in .mcref). That runs in level.tickEntities(), OUTSIDE LocalPlayer.tick(), where
    // the yaw had already been restored to the camera. So every tick the rocket dragged the player
    // back onto the camera direction, and the one tick of WASD the Pre phase set was immediately
    // overpowered -- exactly the reported oscillation, and why every previous pass at the rotation
    // MATH changed nothing.
    // The reference (example-addon-master ControlRocket) never had this because Boze's EventTick.Post
    // is an END-OF-CLIENT-TICK hook, not an inside-the-player-tick one: its flight rotation is live
    // for the whole entity-tick phase, firework included. TickEvent.Post is that same hook here
    // (Minecraft.tick TAIL -- see its own doc), so the restore now happens after every entity has
    // ticked and still before turnPlayer()/renderFrame(), same as the reference.
    @SubscribeEvent
    public void onUpdateMovementPost(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || !crPendingFire) return;
        crPendingFire = false;

        try {
            // 2026-08-19 FIX (reported: "ConserveDelay lúc nhanh lúc chậm, và double jump xong có
            // lúc không bay lên"). ROOT CAUSE, two halves of the same mistake -- the old gate was a
            // TICK COUNTER decremented HERE:
            //   * this handler only runs when crPendingFire is set, which onPlayerUpdate only does on
            //     ticks where isFallFlying() AND a direction/space/shift key is held. Let go of the
            //     keys for a moment and the counter simply stopped counting down; the "delay" you
            //     actually got was however many KEY-HELD ticks happened to pass, not wall time.
            //     That is the inconsistency, and no amount of retuning the number fixes it.
            //   * the `if (onGround()) crRocketCooldown = 0` reset sat inside this same
            //     crPendingFire-gated block -- and crPendingFire can never be true while grounded
            //     (onPlayerUpdate returns early on !isFallFlying()), so it was dead code from the day
            //     it was written. A leftover cooldown therefore SURVIVED the landing and ate the
            //     first N ticks of the next takeoff: double jump, no rocket, fall back down.
            // Ported to hachimi-2.0-src ElytraFlyModule's own shape instead (fireworkTimer, a
            // real-time CacheTimer checked with passed(delay * 1000)): wall-clock elapsed time,
            // sampled wherever the gate happens to run, plus an explicit reset on landing in
            // onPlayerUpdate (which does run every tick, grounded included).
            if (System.currentTimeMillis() - crLastRocketTime < (long) (crConserveDelay.getValue().floatValue() * 50.0f)) return;

            // Reported: "ControlRocket không ăn được [táo vàng] khi đang bay" -- switchSlot below
            // sends ServerboundSetCarriedItemPacket, and vanilla's own handleSetCarriedItem calls
            // player.stopUsingItem() when the carried slot changes WHILE the item being used is in
            // MAINHAND (verified in .mcref -- offhand use is untouched, GrimV3's own armor-slot
            // container clicks never call stopUsingItem at all regardless of hand). Skip firing
            // this tick only for that one case -- crPendingFire re-arms next tick if the key's
            // still held, so this only delays the boost until the mainhand bite/drink/draw
            // finishes, same as the existing breakMultitask/placeMultitask "don't interrupt
            // eating" pattern elsewhere in this codebase. Offhand eating (e.g. golden apple held
            // there) is left alone since vanilla never interrupts it for this reason anyway.
            if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;

            int previousSlot = mc.player.getInventory().getSelectedSlot();
            int slot = InventoryUtils.findHotbar(Items.FIREWORK_ROCKET);
            if (slot == -1) return;

            if (!InventoryUtils.switchSlot(CR_SWITCH, slot, previousSlot)) return;
            // The rotation in this packet is NOT cosmetic: handleUseItem does
            // `player.absSnapRotationTo(packet.getYRot(), packet.getXRot())` BEFORE gameMode.useItem
            // spawns the firework, and FireworkRocketEntity#tick boosts along
            // attachedToEntity.getLookAngle() every tick. Sending crSavedYaw/crSavedPitch (the CAMERA)
            // snapped the server's player back to the camera direction each tick and undid the whole
            // WASD steering -- "mouse steers, WASD does nothing". Send the live rotation, which is
            // still targetYaw/targetPitch here (the restore below runs in the finally, after this),
            // exactly like vanilla MultiPlayerGameMode#useItem does with player.getYRot()/getXRot().
            float fireYaw = mc.player.getYRot(), firePitch = mc.player.getXRot();
            NetworkUtils.sendSequencedPacket(sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, fireYaw, firePitch));
            InventoryUtils.switchBack(CR_SWITCH, slot, previousSlot);

            crLastRocketTime = System.currentTimeMillis();
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

    // GrimV3 -- rewritten to match the verified real implementation (hachimi-2.0-src's
    // ElytraFlyModule, net.hachimi.client.impl.module.movement): the whole cycle is SIX container
    // clicks done SYNCHRONOUSLY within one tick (swapEquipment's own 3-click shape, called twice
    // back to back), not a park-this-tick/unpark-next-tick split across two separate calls.
    //
    // Rest state under GrimV3 is CHESTPLATE-worn, not elytra -- the elytra lives in inventory the
    // whole time and only exists on the chest slot for the instant between the two swapEquipment
    // calls below. Server packet processing drains ALL queued packets for a connection before that
    // tick's own entity update runs, so by the time updateFallFlying()/canGlide() would evaluate,
    // both swaps and the START_FALL_FLYING action have already landed in order -- the elytra was
    // "on" for exactly the packet that needed it (that one), then back off before the entity tick
    // proper. isFallFlying, once set, persists independent of subsequent equipment changes (only
    // cleared by canGlide() failing on a LATER entity tick, or landing) -- so it sticks.
    //
    // This also fixes what the old two-phase version (real, single-tick-park design) got backwards:
    // it gated the WHOLE cycle behind `isFallFlying() == true` as a PRECONDITION -- circular, since
    // the swap is what's supposed to START flying in the first place. A double-jump could never
    // bootstrap anything because the cycle refused to run until you were already flying. Gate is
    // now just "airborne + throttle", same as the reference's own `packetDelayInt > grimDelayConfig`
    // check -- runs for a fresh takeoff exactly the same as a mid-flight refresh.
    //
    // No render lie needed anymore either: the flash-in/flash-out is fully synchronous within this
    // one method call, well before this tick's frame ever renders -- by the time anything draws,
    // the chest slot is already back to the chestplate. getGrimParkedSlot()/getGrimHiddenElytra()
    // already went permanently inert in earlier passes; nothing here needs to un-inert them.
    private void grimSwapCycle() {
        if (!crGrimSwap.getValue() || mc.player.onGround()) return;
        if (crGrimTicks++ < crGrimDelay.getValue().intValue()) return;
        crGrimTicks = 0;

        sendGlideStart();
    }

    /** START_FALL_FLYING the server will actually accept. tryToStartFallFlying requires canGlide(),
     *  i.e. an elytra sitting on the chest slot -- which is NOT the rest state under GrimV3
     *  (chestplate worn, elytra in the inventory, see grimSwapCycle's own doc), so a bare send is
     *  silently dropped there. Flash the elytra on around the packet and put the chestplate
     *  straight back: the server drains a connection's whole packet queue before ticking the
     *  entity, so both swaps and the action land in order and the elytra was "on" for exactly the
     *  packet that needed it. Also the entire GrimV3 cycle -- same three sends, same reason. */
    private void sendGlideStart() {
        crGliding = true;
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return;
        }

        int elytraSlot = InventoryUtils.find(Items.ELYTRA);
        if (elytraSlot == -1) return;

        crLastSwapTimer.reset();
        InventoryUtils.swapEquipment(elytraSlot, 6); // elytra ON (chestplate displaced into elytraSlot)
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        InventoryUtils.swapEquipment(elytraSlot, 6); // elytra OFF again (chestplate restored)
    }

    /** 2026-08-19, the REAL remaining GrimV3 "pop". The armor model was never the culprit: every
     *  swapEquipment click goes through MultiPlayerGameMode#handleContainerInput, which resolves
     *  AbstractContainerMenu#clicked SYNCHRONOUSLY on the client before it even sends the packet
     *  (verified in .mcref) -- both swaps and the action complete inside one method call, so no
     *  frame can ever render between them and HumanoidArmorLayer/WingsLayer (both read
     *  HumanoidRenderState.chestEquipment) can never observe the gap. What DOES flicker is the
     *  server's own fall-flying flag, echoed straight back to us:
     *    * ServerEntity#sendDirtyEntityData uses sendToTrackingPlayersAndSelf -- unlike equipment
     *      (plain broadcast), entity DATA for our own player is sent to us as well;
     *    * LivingEntity#updateFallFlying does `if (!canGlide()) { setSharedFlag(7,false); return; }`
     *      and canGlide() needs the elytra ON the chest slot -- which under GrimV3 it only is for
     *      the instant between the two swaps, never at the server's entity tick. So on every tick
     *      grimSwapCycle does NOT fire (crGrimDelay ticks out of every crGrimDelay+1), the server
     *      clears the flag and tells us so; the next cycle's START_FALL_FLYING sets it again.
     *  Client-side that flag drives, every other tick: Player#updatePlayerPose (Pose.FALL_FLYING vs
     *  STANDING -- a ~1.2-block eye-height jump, i.e. the camera pop), LivingEntity#travel
     *  (travelFallFlying vs plain air physics), FireworkRocketEntity#tick (the boost is gated on
     *  attachedToEntity.isFallFlying(), so thrust pulsed on/off), and fallFlyTicks resetting to 0.
     *  That is the jolt, not an equip animation.
     *  Pin it true locally for the duration of the flight -- same client-only-illusion category as
     *  HumanoidMobRendererMixin's isFallFlying=false render lie, applied one layer lower. GrimV3
     *  only: with it off the elytra is genuinely worn, the server keeps the flag set on its own and
     *  there is nothing to paper over. See LivingEntityMixin.euclient$pinControlRocketGlide. */
    public boolean shouldPinFallFlying() {
        return isToggled() && mode.getValue().equalsIgnoreCase("ControlRocket") && crGrimSwap.getValue()
                && crGliding && mc.player != null && !mc.player.onGround();
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

    /** Used to return the parked elytra's inventory index so GuiHotbarMixin/HandledScreenMixin
     *  could keep showing the PRE-swap item (a chestplate) at that slot instead of the real elytra
     *  that lands there for the cycle. Now always -1 (never lies) -- reported: "elytra của tôi
     *  biến thành chestplate, thành ra là bị dupe ảo thêm 1 chestplate". The park swap uses a REAL
     *  chestplate (findChestplateSlot(), not an empty slot -- see grimSwapCycle's own doc), so
     *  while parked the player is genuinely WEARING that chestplate (getGrimHiddenElytra() already
     *  stopped hiding that, see its own doc) -- but this GUI lie kept showing the SAME chestplate
     *  icon still sitting in its original inventory slot too, on top of the real one now worn:
     *  looked like two chestplates existed (one worn, one "still" in inventory) when there was
     *  only ever one, now genuinely worn, with the inventory slot correctly holding the elytra. */
    public int getGrimParkedSlot() {
        return -1;
    }

    /** See getGrimParkedSlot()'s own doc -- no longer read for anything now that slot always
     *  reports -1, kept only so grimSwapCycle()'s bookkeeping (crParkedDisplaced) still compiles. */
    public net.minecraft.world.item.ItemStack getGrimParkedDisplaced() {
        return crParkedDisplaced;
    }

    /** Used to always return the parked elytra here so LivingEntityMixin could hide the one real
     *  non-gliding tick from the 3rd-person model (wings/pose), keeping "Gliding" shown the whole
     *  cycle. Reported: "khi bay tôi vẫn thấy Gliding chứ không như đang sprint+walk như bay ở
     *  sáng tạo" -- the swap really does clear FALL_FLYING server-side for that tick (that's the
     *  whole mechanism), so stop hiding it: null unconditionally lets LivingEntityMixin's
     *  getItemBySlot injection see the real (empty/chestplate) chest slot and the model genuinely
     *  drop to a walk/sprint pose for that one tick, same as what's actually happening server-side.
     *  GUI-only lies (hotbar/inventory slot icon staying stable) are untouched -- see
     *  getGrimParkedSlot()/getGrimParkedDisplaced(), a separate cosmetic concern. */
    public net.minecraft.world.item.ItemStack getGrimHiddenElytra() {
        return null;
    }

    @Override
    public void onDisable() {
        crPendingFire = false;
        crCameraOverrideActive = false;
        crGrimTicks = 0;
        crLaunchPending = false;
        crGliding = false;
        crLastRocketTime = 0L;
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