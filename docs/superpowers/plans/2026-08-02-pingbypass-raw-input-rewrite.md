# PingBypass Raw-Input Architecture Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `proxyEnhanced`/`shouldRunOnProxy()`/`isRunningOnProxy()` dumb-pipe architecture with raw-input-forwarding (client forwards keyboard/mouse only; the proxy's own full Minecraft client instance simulates movement/actions and is the single source of every outgoing packet), mirroring `3arthqu4ke/3arthh4ck`'s pattern. Eliminates the "2-source" packet races (rotation rubberband, AutoCrystal slot races) that dumb-pipe forwarding + proxy-injected patches cannot fully remove.

**Architecture:** Client stops building/sending real gameplay packets locally; it only forwards raw input events over the existing custom-payload channel. The proxy — which is itself a full `Minecraft.getInstance()` client process (see `IMinecraft.mc`) that connects to the real backend server and exposes a `ProxyServer` Netty listener the real player's client connects to instead — replays that raw input into its own `LocalPlayer`/`KeyMapping` state each tick, so the existing vanilla game loop (movement, attack cooldown, block breaking) generates every packet exactly once, on the proxy only. Modules needing proxy-only, tick-precise player tracking (AutoCrystal, AutoTotem, Surround, SpeedMine) get standalone proxy-side classes instead of `if (isRunningOnProxy())` branches inside the shared client `Module`.

**Tech Stack:** Java 21, Fabric (Minecraft 26.1.2, Mojmap), Mixin, JUnit 5 + Mockito + jqwik (existing `src/test/java/eu/client/pingbypass/**`).

## Global Constraints

- Do not modify `eu.client.modules.Module` / `eu.client.modules.ModuleManager` signatures used by non-pingbypass modules — the new `pingbypass/modules/` package is additive, existing client-only modules must keep working unchanged.
- No new external dependencies. Use only what's already in `build.gradle` (jqwik 1.9.2, junit-jupiter 5.11.4, mockito-core 5.18.0).
- Preserve the existing custom-payload channel plumbing (`PbCustomPayload`, `PbPacket`, `PbProtocolHandler`) for all new packet types — do not invent a second transport.
- Step 6 migration scope is **restricted to 4 modules only**: `AutoCrystalModule`, `AutoTotemModule`, `SpeedMineModule`, `SurroundModule`. Do not touch `AutoArmorModule`, `CriticalsModule`, `SelfFillModule`, `ThrowXPModule`, `AutoTrapModule`, `AutoWebModule`, `SelfTrapModule`, `HoleBuilder`, `HoleFillModule`, `OpponentScaffold`, `KillAuraModule`, `CombatPlace`, `BlockerModule`, `FakePlayerModule` — leave their `proxyEnhanced`/`isRunningOnProxy()` code exactly as-is for now.
- Testing convention for this codebase: pure logic (packet (de)serialization, state trackers, dedupe/jitter math, registries) gets real JUnit/Mockito/jqwik tests under `src/test/java/...` (follow the style of the existing `ProxyModuleManagerTest`). Code that only runs inside a live Minecraft instance (mixins, actual tick-loop behavior, feel/latency) has **no unit-test path in this codebase** — verification is: `./gradlew build` succeeds, then the existing manual workflow (build jar → copy to `example-addon-master/run/mods/` → `gradlew.bat runBoze`) to catch mixin/runtime crashes, plus live in-game check per module. State this explicitly in each such task instead of fabricating a fake test.

## Critical existing-code finding (read before Task 1)

`src/main/java/eu/client/pingbypass/modules/ProxyModuleManager.java` (+ its tests `src/test/java/eu/client/pingbypass/modules/ProxyModuleManagerTest.java` and `ProxyModuleIndependencePropertyTest.java`) is a **previously-built, still-live piece of the architecture this plan abandons**: it hands the proxy's own `ModuleManager` the *same* `AutoCrystalModule`/`SurroundModule`/`SpeedMineModule`/etc. instances so they tick under `isRunningOnProxy()` branches. Task 12 deletes this class and its tests once `PbModuleManager` (new, package-identical name collision avoided by keeping `ProxyModuleManager` deleted, not renamed) replaces its job for the 4 in-scope modules. Grep `ProxyModuleManager` for any other caller before deleting (currently only its own tests reference it — confirm this hasn't changed).

---

## File Structure

```
src/main/java/eu/client/pingbypass/
├── input/
│   ├── ClientInputService.java     (NEW — client: capture raw key/mouse, send C2S packets)
│   └── ServerInputService.java     (NEW — proxy: consume raw input, drive proxy's own LocalPlayer)
├── protocol/packets/
│   ├── C2SInputKeyPacket.java      (NEW — replaces C2SInputPacket for key press/release)
│   └── C2SInputLookPacket.java     (NEW — mouse-look delta, continuous)
├── modules/
│   ├── PbModule.java               (NEW — base: wraps a client Module's settings for proxy display)
│   ├── SyncModule.java             (NEW — pushes/pulls Setting values between client Module and PbModule)
│   ├── PbModuleManager.java        (NEW — registry of PbModule instances; replaces ProxyModuleManager)
│   └── submodules/
│       ├── crystal/ServerAutoCrystal.java   (NEW)
│       ├── totem/ServerAutoTotem.java       (NEW)
│       ├── surround/ServerSurround.java     (NEW)
│       └── speedmine/ServerSpeedMine.java   (NEW — raw-input-only, thin: no proxy branch needed, see Task 11)
├── PingBypassFlags.java            (MODIFY — remove clientOnGround/clientHorizontalCollision, add rawInputForwardingActive)
└── handler/PbPlayHandler.java      (MODIFY — route new packets to ServerInputService, delete yaw/pitch override patch)

DELETE:
  src/main/java/eu/client/pingbypass/input/ClientInputForwarder.java
  src/main/java/eu/client/pingbypass/protocol/packets/C2SInputPacket.java
  src/main/java/eu/client/pingbypass/modules/ProxyModuleManager.java
  src/test/java/eu/client/pingbypass/modules/ProxyModuleManagerTest.java
  src/test/java/eu/client/pingbypass/modules/ProxyModuleIndependencePropertyTest.java

MODIFY:
  src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java   (cancel local move/attack/dig when raw-input active)
  src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java   (strip proxy branches — see grep list Task 9)
  src/main/java/eu/client/modules/impl/combat/AutoTotemModule.java    (strip proxy branches)
  src/main/java/eu/client/modules/impl/combat/SurroundModule.java     (strip proxy branches)
  src/main/java/eu/client/modules/impl/player/SpeedMineModule.java    (strip proxy branches)
```

---

## Task 1: Delete dead input infrastructure

**Files:**
- Delete: `src/main/java/eu/client/pingbypass/input/ClientInputForwarder.java`
- Delete: `src/main/java/eu/client/pingbypass/protocol/packets/C2SInputPacket.java`

**Interfaces:** none — nothing else references `C2SInputPacket` (confirmed: grep `handlePbPayload`/`PbPlayHandler` shows no case for ID 10, so the proxy side never consumed it; `ClientInputForwarder` is only self-registered, no external caller).

- [ ] **Step 1:** `grep -rn "ClientInputForwarder\|C2SInputPacket" src/main/java` — confirm zero remaining references outside the two files being deleted.
- [ ] **Step 2:** Delete both files.
- [ ] **Step 3:** `./gradlew compileJava` — confirm the build still compiles (proves nothing depended on them).
- [ ] **Step 4:** Commit.

```bash
git add -A -- src/main/java/eu/client/pingbypass/input/ClientInputForwarder.java src/main/java/eu/client/pingbypass/protocol/packets/C2SInputPacket.java
git commit -m "pingbypass: remove dead raw-input scaffolding (2-button only, no proxy consumer)"
```

---

## Task 2: New raw-input packets

**Files:**
- Create: `src/main/java/eu/client/pingbypass/protocol/packets/C2SInputKeyPacket.java`
- Create: `src/main/java/eu/client/pingbypass/protocol/packets/C2SInputLookPacket.java`
- Test: `src/test/java/eu/client/pingbypass/protocol/packets/C2SInputKeyPacketTest.java`
- Test: `src/test/java/eu/client/pingbypass/protocol/packets/C2SInputLookPacketTest.java`

**Interfaces:**
- Consumes: `eu.client.pingbypass.protocol.PbPacket` (base class — look at `C2SFriendSyncPacket.java` for the exact `getPacketId()`/`write(FriendlyByteBuf)`/`FriendlyByteBuf`-constructor pattern already used in this codebase).
- Produces: `C2SInputKeyPacket.Action` enum (`PRESS`, `RELEASE`), `C2SInputKeyPacket.Key` enum (`FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE`) consumed by `ServerInputService` (Task 4). `C2SInputLookPacket(float deltaX, float deltaY)` consumed by `ServerInputService`.

- [ ] **Step 1: Write `C2SInputKeyPacket`**

```java
package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: a single raw key/mouse-button transition (not a continuous state poll).
 * Packet ID: 11 (10 is retired, was C2SInputPacket).
 */
public class C2SInputKeyPacket extends PbPacket {
    public static final int ID = 11;

    public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE }
    public enum Action { PRESS, RELEASE }

    private final Key key;
    private final Action action;

    public C2SInputKeyPacket(Key key, Action action) {
        this.key = key;
        this.action = action;
    }

    public C2SInputKeyPacket(FriendlyByteBuf buf) {
        this.key = buf.readEnum(Key.class);
        this.action = buf.readEnum(Action.class);
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(key);
        buf.writeEnum(action);
    }

    public Key getKey() { return key; }
    public Action getAction() { return action; }
}
```

- [ ] **Step 2: Write `C2SInputLookPacket`**

```java
package eu.client.pingbypass.protocol.packets;

import eu.client.pingbypass.protocol.PbPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -> Proxy: accumulated mouse-look delta since the last tick.
 * Packet ID: 12.
 */
public class C2SInputLookPacket extends PbPacket {
    public static final int ID = 12;

    private final float deltaX;
    private final float deltaY;

    public C2SInputLookPacket(float deltaX, float deltaY) {
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }

    public C2SInputLookPacket(FriendlyByteBuf buf) {
        this.deltaX = buf.readFloat();
        this.deltaY = buf.readFloat();
    }

    @Override public int getPacketId() { return ID; }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(deltaX);
        buf.writeFloat(deltaY);
    }

    public float getDeltaX() { return deltaX; }
    public float getDeltaY() { return deltaY; }
}
```

- [ ] **Step 3: Register both packet IDs.** Open `src/main/java/eu/client/pingbypass/protocol/PbPacketHandler.java` (or wherever `PbPacket` subclasses are dispatched by ID — check `PbProtocolHandler.java` for the existing ID->constructor switch used by `C2SFriendSyncPacket`/ID 9-ish) and add `case 11 -> new C2SInputKeyPacket(buf);` / `case 12 -> new C2SInputLookPacket(buf);` following the exact existing pattern for the other `C2S*` packets in that switch.

- [ ] **Step 4: Round-trip tests**

```java
package eu.client.pingbypass.protocol.packets;

import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class C2SInputKeyPacketTest {
    @Test
    void roundTrip_preservesKeyAndAction() {
        C2SInputKeyPacket original = new C2SInputKeyPacket(C2SInputKeyPacket.Key.FORWARD, C2SInputKeyPacket.Action.PRESS);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputKeyPacket decoded = new C2SInputKeyPacket(buf);

        assertEquals(C2SInputKeyPacket.Key.FORWARD, decoded.getKey());
        assertEquals(C2SInputKeyPacket.Action.PRESS, decoded.getAction());
        assertEquals(11, decoded.getPacketId());
    }
}
```

```java
package eu.client.pingbypass.protocol.packets;

import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class C2SInputLookPacketTest {
    @Test
    void roundTrip_preservesDeltas() {
        C2SInputLookPacket original = new C2SInputLookPacket(1.5f, -2.25f);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        C2SInputLookPacket decoded = new C2SInputLookPacket(buf);

        assertEquals(1.5f, decoded.getDeltaX(), 0.0001f);
        assertEquals(-2.25f, decoded.getDeltaY(), 0.0001f);
    }
}
```

- [ ] **Step 5:** `./gradlew test --tests "eu.client.pingbypass.protocol.packets.*"` — expect both PASS.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/eu/client/pingbypass/protocol/packets/C2SInputKeyPacket.java src/main/java/eu/client/pingbypass/protocol/packets/C2SInputLookPacket.java src/test/java/eu/client/pingbypass/protocol/packets/C2SInputKeyPacketTest.java src/test/java/eu/client/pingbypass/protocol/packets/C2SInputLookPacketTest.java
git commit -m "pingbypass: add raw key/look packets to replace dead C2SInputPacket"
```

---

## Task 3: `ClientInputService` — capture raw input on the client

**Files:**
- Create: `src/main/java/eu/client/pingbypass/input/ClientInputService.java`

**Interfaces:**
- Consumes: `eu.client.mixins.accessors.KeyboardHandlerAccessor.invokeKeyPress` and `eu.client.mixins.accessors.MouseAccessor.invokeOnButton` (both already exist, currently unused by anything except `EURoboticsModule` for the mouse one — confirm no conflict, both are `@Invoker` mixins so multiple callers are fine) as the raw signal source; `Minecraft.getInstance().options.keyUp/keyDown/keyJump/keySprint/keyAttack/keyUse` (`KeyMapping` instances) to map GLFW key codes to the semantic `C2SInputKeyPacket.Key`.
- Produces: calls `mc.getConnection().getConnection().send(new ServerboundCustomPayloadPacket(PbCustomPayload.fromPacket(...)))` exactly like `Module.sendProxyToggle` does (line 211-222 of `Module.java` — copy that pattern, it's the established way to send a `PbPacket` from client code).
- Gated by: `eu.client.pingbypass.PingBypassFlags.rawInputForwardingActive` (new flag, added in Task 8) — when false, this service must do nothing (so it's inert until the whole rewrite is wired up and flipped on).

- [ ] **Step 1: Write the service**

```java
package eu.client.pingbypass.input;

import eu.client.EUClient;
import eu.client.pingbypass.PingBypassFlags;
import eu.client.pingbypass.protocol.PbCustomPayload;
import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;
import eu.client.pingbypass.protocol.packets.C2SInputLookPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Client-side raw input capture. When PingBypassFlags.rawInputForwardingActive is true,
 * every semantic key transition and accumulated mouse-look delta is forwarded to the proxy
 * instead of letting the local LocalPlayer act on it directly (see ClientPlayerEntityMixin,
 * Task 8, for the corresponding local-action cancellation).
 */
public class ClientInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private float accumulatedDeltaX = 0f;
    private float accumulatedDeltaY = 0f;

    public void onKeyTransition(C2SInputKeyPacket.Key key, C2SInputKeyPacket.Action action) {
        if (!PingBypassFlags.rawInputForwardingActive) return;
        send(new C2SInputKeyPacket(key, action));
    }

    /** Called from the mouse-move mixin hook with the raw, unscaled cursor delta for this event. */
    public void onMouseMove(double dx, double dy) {
        if (!PingBypassFlags.rawInputForwardingActive) return;
        accumulatedDeltaX += (float) dx;
        accumulatedDeltaY += (float) dy;
    }

    /** Called once per client tick to flush the accumulated look delta as a single packet. */
    public void flushLookDelta() {
        if (!PingBypassFlags.rawInputForwardingActive) return;
        if (accumulatedDeltaX == 0f && accumulatedDeltaY == 0f) return;
        send(new C2SInputLookPacket(accumulatedDeltaX, accumulatedDeltaY));
        accumulatedDeltaX = 0f;
        accumulatedDeltaY = 0f;
    }

    private void send(eu.client.pingbypass.protocol.PbPacket packet) {
        try {
            if (mc.getConnection() == null) return;
            var payload = PbCustomPayload.fromPacket(packet);
            mc.getConnection().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Exception e) {
            EUClient.LOGGER.warn("[PingBypass] Failed to forward raw input", e);
        }
    }
}
```

- [ ] **Step 2: Wire key capture.** In `KeyboardHandlerAccessor`'s consumer — add a `@Inject` mixin (new file `src/main/java/eu/client/mixins/KeyboardHandlerMixin.java`, or extend an existing one if the codebase already has a `KeyboardHandler` mixin — `grep -rn "@Mixin(KeyboardHandler.class)" src/main/java` first) at `head` of `keyPress` that maps `(key, scancode, action, mods)` via `mc.options.keyUp.matches(...)` etc. to the semantic `Key` enum and calls `EUClient.PB_CLIENT_INPUT.onKeyTransition(...)`. Add `attack`/`use` from the existing mouse-button mixin similarly (`MouseAccessor`/its mixin consumer).
- [ ] **Step 3: Wire mouse-look capture.** `grep -rn "@Mixin(MouseHandler.class)" src/main/java` — find or add the injection at `MouseHandler#onMove` (or `turnPlayer`) HEAD, forwarding the raw unscaled `xOffset`/`yOffset` to `onMouseMove`.
- [ ] **Step 4: Wire the per-tick flush.** In the existing client tick event path (`EUClient.EVENT_HANDLER` subscriber list — follow the pattern of any existing `@SubscribeEvent` on `PlayerUpdateEvent`), call `flushLookDelta()` once per tick.
- [ ] **Step 5: Register a singleton.** Add `public static final ClientInputService PB_CLIENT_INPUT = new ClientInputService();` to `EUClient.java` next to the other `public static final` proxy singletons (follow existing convention — check how `EUClient.PROXY_SERVER` is declared).
- [ ] **Step 6: Build check.** `./gradlew compileJava` — must compile. No unit test here: this task is 100% mixin wiring against live GLFW/Minecraft input, which this codebase has no test harness for (see Global Constraints). Verification is build success now; live feel-check happens after Task 8 wires the whole pipeline end-to-end.
- [ ] **Step 7: Commit**

```bash
git add src/main/java/eu/client/pingbypass/input/ClientInputService.java src/main/java/eu/client/mixins/ src/main/java/eu/client/EUClient.java
git commit -m "pingbypass: add ClientInputService, wire raw key/mouse capture"
```

---

## Task 4: `ServerInputService` — replay raw input on the proxy

**Files:**
- Create: `src/main/java/eu/client/pingbypass/input/ServerInputService.java`
- Test: `src/test/java/eu/client/pingbypass/input/ServerInputServiceTest.java`

**Interfaces:**
- Consumes: `C2SInputKeyPacket`, `C2SInputLookPacket` routed from `PbPlayHandler` (Task 5). Drives `Minecraft.getInstance().player` (the proxy's own `LocalPlayer` — same `mc` instance the existing `Module` code already reads via `IMinecraft.mc`) via `KeyMapping.set(net.minecraft.client.KeyMapping, boolean)` (public static method — vanilla lets you flip a mapping's `isDown`/pressed count directly, this is how the class already tracks held-state without a live keyboard) and `LivingEntity#turn(float yaw, float pitch)` (protected on `Entity`, package-visible enough for a mixin accessor if needed — if `turn` isn't accessible, add a 1-line `@Invoker` accessor mixin for it, same pattern as `KeyboardHandlerAccessor`).
- Produces: nothing external — this *is* the terminal consumer; the proxy's normal tick loop (unmodified) reacts to the `KeyMapping` state and turned rotation exactly as it would to real hardware input, and emits the real `Serverbound*` packets itself.

- [ ] **Step 1: Write the key-state part first, test it in isolation.** The mapping from `Key` enum to which `KeyMapping` to toggle is pure logic — extract it as a small pure function so it's testable without booting Minecraft:

```java
package eu.client.pingbypass.input;

import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tracks which semantic keys are currently held, purely as state — no Minecraft
 * dependency, so it's unit-testable. ServerInputService applies this state to the
 * proxy's real KeyMapping objects each tick.
 */
public class KeyState {
    private final Map<C2SInputKeyPacket.Key, Boolean> held = new EnumMap<>(C2SInputKeyPacket.Key.class);

    public void apply(C2SInputKeyPacket.Key key, C2SInputKeyPacket.Action action) {
        held.put(key, action == C2SInputKeyPacket.Action.PRESS);
    }

    public boolean isHeld(C2SInputKeyPacket.Key key) {
        return held.getOrDefault(key, false);
    }
}
```

- [ ] **Step 2: Test `KeyState`**

```java
package eu.client.pingbypass.input;

import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;
import org.junit.jupiter.api.Test;
import static eu.client.pingbypass.protocol.packets.C2SInputKeyPacket.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyStateTest {
    @Test
    void unpressedKey_defaultsToNotHeld() {
        assertFalse(new KeyState().isHeld(Key.FORWARD));
    }

    @Test
    void press_thenRelease_returnsToNotHeld() {
        KeyState state = new KeyState();
        state.apply(Key.FORWARD, Action.PRESS);
        assertTrue(state.isHeld(Key.FORWARD));
        state.apply(Key.FORWARD, Action.RELEASE);
        assertFalse(state.isHeld(Key.FORWARD));
    }

    @Test
    void keysAreIndependent() {
        KeyState state = new KeyState();
        state.apply(Key.FORWARD, Action.PRESS);
        assertFalse(state.isHeld(Key.SPRINT));
    }
}
```

- [ ] **Step 3: Run `./gradlew test --tests "eu.client.pingbypass.input.KeyStateTest"` — expect PASS.**

- [ ] **Step 4: Write `ServerInputService`** (the Minecraft-touching part — no unit test, per Global Constraints; this is where `KeyState` gets applied to real `KeyMapping`s and where mouse-look deltas turn the proxy's own player each tick):

```java
package eu.client.pingbypass.input;

import eu.client.pingbypass.protocol.packets.C2SInputKeyPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Proxy-side: replays a real client's raw input into this proxy's own LocalPlayer,
 * so the normal vanilla tick loop (movement, attack, mining) runs exactly once,
 * driven by data instead of hardware, and emits every gameplay packet itself.
 */
public class ServerInputService {
    private final Minecraft mc = Minecraft.getInstance();
    private final KeyState keyState = new KeyState();
    private float pendingYawDelta = 0f;
    private float pendingPitchDelta = 0f;

    public void onKeyTransition(C2SInputKeyPacket.Key key, C2SInputKeyPacket.Action action) {
        keyState.apply(key, action);
        applyToMapping(key);
    }

    public void onLookDelta(float deltaX, float deltaY) {
        pendingYawDelta += deltaX;
        pendingPitchDelta += deltaY;
    }

    /** Call once per proxy tick, before the vanilla player tick runs. */
    public void tick() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (pendingYawDelta != 0f || pendingPitchDelta != 0f) {
            player.turn(pendingYawDelta, pendingPitchDelta);
            pendingYawDelta = 0f;
            pendingPitchDelta = 0f;
        }
    }

    private void applyToMapping(C2SInputKeyPacket.Key key) {
        boolean down = keyState.isHeld(key);
        KeyMapping mapping = switch (key) {
            case FORWARD, BACK, LEFT, RIGHT -> null; // movement keys read via a MovementInput accessor, see Step 5
            case JUMP -> mc.options.keyJump;
            case SNEAK -> mc.options.keyShift;
            case SPRINT -> mc.options.keySprint;
            case ATTACK -> mc.options.keyAttack;
            case USE -> mc.options.keyUse;
        };
        if (mapping != null) KeyMapping.set(mapping.getKey(), down);
    }
}
```

- [ ] **Step 5: Movement keys need `KeyboardInput`, not just `KeyMapping.set`.** `LocalPlayer`'s forward/strafe movement is read from `Minecraft.getInstance().player.input` (a `KeyboardInput`/`Input` object), which itself reads `options.keyUp/keyDown/keyLeft/keyRight.isDown()` each tick — so `KeyMapping.set(mc.options.keyUp.getKey(), down)` for `FORWARD`/`BACK`/`LEFT`/`RIGHT` **is** sufficient (same mechanism as jump/sneak/sprint). Fix the `switch` above to also map `FORWARD -> mc.options.keyUp`, `BACK -> mc.options.keyDown`, `LEFT -> mc.options.keyLeft`, `RIGHT -> mc.options.keyRight`, and drop the `null` case.
- [ ] **Step 6: Register a singleton and hook the tick.** Add `public static final ServerInputService PB_SERVER_INPUT = new ServerInputService();` to `EUClient.java`. Find the proxy's own tick listener (`src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java`) and call `EUClient.PB_SERVER_INPUT.tick()` from it, before the existing per-tick logic.
- [ ] **Step 7: Build check.** `./gradlew compileJava`.
- [ ] **Step 8: Commit**

```bash
git add src/main/java/eu/client/pingbypass/input/ServerInputService.java src/main/java/eu/client/pingbypass/input/KeyState.java src/test/java/eu/client/pingbypass/input/KeyStateTest.java src/main/java/eu/client/EUClient.java src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java
git commit -m "pingbypass: add ServerInputService, replay raw input into proxy's LocalPlayer"
```

---

## Task 5: Route the new packets through `PbPlayHandler`

**Files:**
- Modify: `src/main/java/eu/client/pingbypass/handler/PbPlayHandler.java`
- Modify: whichever file dispatches `PbPacket` by ID on the proxy side to module handlers (`grep -rn "handlePbPayload\|PbProtocolHandler" src/main/java/eu/client/pingbypass` to find it precisely before editing)

**Interfaces:**
- Consumes: `C2SInputKeyPacket`, `C2SInputLookPacket` (Task 2), `EUClient.PB_SERVER_INPUT` (Task 4).
- Produces: nothing new — this task only adds a `case` to the existing payload dispatch and a call into `ServerInputService`.

- [ ] **Step 1:** Find the exact dispatch method (`PbPlayHandler.handlePbPayload` or similar — confirm name by reading the file's custom-payload handling section) and add:

```java
} else if (packet instanceof C2SInputKeyPacket keyPacket) {
    EUClient.PB_SERVER_INPUT.onKeyTransition(keyPacket.getKey(), keyPacket.getAction());
} else if (packet instanceof C2SInputLookPacket lookPacket) {
    EUClient.PB_SERVER_INPUT.onLookDelta(lookPacket.getDeltaX(), lookPacket.getDeltaY());
}
```

matching the existing `else if (packet instanceof ...)` chain style used for `C2SFriendSyncPacket` etc. in that same method.

- [ ] **Step 2:** `./gradlew compileJava`.
- [ ] **Step 3: Commit**

```bash
git add src/main/java/eu/client/pingbypass/handler/PbPlayHandler.java
git commit -m "pingbypass: route raw-input packets from PbPlayHandler to ServerInputService"
```

---

## Task 6: Cancel local client-side action packets when raw-input forwarding is active

**Files:**
- Modify: `src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java`
- Modify: `src/main/java/eu/client/pingbypass/PingBypassFlags.java` (add the new flag here first — pulled forward from Task 8 since this task needs it to compile)

**Interfaces:**
- Consumes: new flag `PingBypassFlags.rawInputForwardingActive` (volatile boolean, default `false` — mirrors the existing `proxyForwardingActive` flag's declaration style).
- Produces: local movement/attack fully frozen client-side while the flag is on; `ClientInputService` (Task 3) is the only remaining source of local player intent.

- [ ] **Step 1:** Add to `PingBypassFlags.java`:

```java
/**
 * When true, the CLIENT no longer builds/sends its own movement or action packets at all —
 * ClientInputService forwards raw key/mouse input to the proxy instead, and the proxy's own
 * LocalPlayer (via ServerInputService) is the sole source of every gameplay packet.
 * Only meaningful on the client side; the proxy always ticks its player normally.
 */
public static volatile boolean rawInputForwardingActive = false;
```

- [ ] **Step 2:** In `ClientPlayerEntityMixin.move` (existing `@Inject(method = "move", at = @At("HEAD"), cancellable = true)`), add at the very top of the method body:

```java
if (eu.client.pingbypass.PingBypassFlags.rawInputForwardingActive
        && EUClient.PINGBYPASS_CONFIG != null && !EUClient.PINGBYPASS_CONFIG.isServer()) {
    info.cancel();
    return;
}
```

(client-only: `!isServer()` — the proxy side must keep moving normally, driven by `ServerInputService`.)

- [ ] **Step 3:** In `ClientPlayerEntityMixin.sendMovementPackets`, change the existing client-side "do NOT cancel" branch (lines ~105-108) to also cancel when raw-input forwarding is active:

```java
// On the CLIENT side: only forward movement packets in dumb-pipe mode.
// Under raw-input forwarding, the client never moves locally (see `move` above),
// so there is nothing meaningful to send — the proxy's own LocalPlayer sends real
// movement packets to the backend server itself.
if (eu.client.pingbypass.PingBypassFlags.rawInputForwardingActive
        && EUClient.PINGBYPASS_CONFIG != null && !EUClient.PINGBYPASS_CONFIG.isServer()) {
    info.cancel();
    return;
}
SendMovementEvent event = new SendMovementEvent();
EUClient.EVENT_HANDLER.post(event);
if (event.isCancelled()) {
    info.cancel();
}
```

- [ ] **Step 4:** In `ClientPlayerEntityMixin.swingHand` (attack), add the same client-side guard at the top: if `rawInputForwardingActive && !isServer()`, `info.cancel(); return;` before the existing `SwingModule` logic — local attacks must not swing/send locally; `ATTACK` key transitions go to the proxy instead (Task 3/4 already wire `ATTACK`/`USE` through `mc.options.keyAttack`/`keyUse`).
- [ ] **Step 5:** Digging (block break) is not in this mixin — `grep -rn "ServerboundPlayerActionPacket\|startDestroyBlock\|MultiPlayerGameMode" src/main/java/eu/client/mixins` to find the exact mixin/class that currently issues client digging packets, and add the identical `rawInputForwardingActive && !isServer()` cancel guard at its injection point. Record the file/method found here before editing (this plan cannot name it without that grep — the codebase may route digging through `ClientDiggingService`-equivalent code that doesn't exist yet per the research notes, in which case block-break already only happens via `mc.gameMode` triggered by `KeyMapping.keyUse`/left-click handling in `Minecraft.tick()`, and no separate cancel is needed since nothing here calls it locally when the attack key mapping itself isn't being pressed by real input — confirm by reading `Minecraft.startAttack()`/`continueAttack()` call sites before deciding whether an extra guard is required).
- [ ] **Step 6: Build + manual verification (no unit test — mixin/runtime behavior).** `./gradlew build`, then build the jar, copy to `example-addon-master/run/mods/`, run `gradlew.bat runBoze`, connect through the proxy with `rawInputForwardingActive` temporarily forced `true` via a debug toggle, and confirm: local player does not visibly move/attack from real key presses, while the proxy-side character does move/attack in response to those same key presses (watch proxy's own game window).
- [ ] **Step 7: Commit**

```bash
git add src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java src/main/java/eu/client/pingbypass/PingBypassFlags.java
git commit -m "pingbypass: cancel local movement/attack when raw-input forwarding is active"
```

---

## Task 7: `PbModule` / `SyncModule` / `PbModuleManager` — new proxy-module framework

**Files:**
- Create: `src/main/java/eu/client/pingbypass/modules/PbModule.java`
- Create: `src/main/java/eu/client/pingbypass/modules/SyncModule.java`
- Create: `src/main/java/eu/client/pingbypass/modules/PbModuleManager.java`
- Test: `src/test/java/eu/client/pingbypass/modules/PbModuleManagerTest.java`

**Interfaces:**
- Consumes: `eu.client.modules.Module` (existing, read-only — for name/settings introspection), `eu.client.settings.Setting` (existing).
- Produces: `PbModule.getName()`, `PbModule.getSettings()`, `PbModule.isToggled()`/`setToggled(boolean)`, `PbModule.tick()` (abstract — implemented by each submodule in Task 9), consumed by `PbModuleManager.getModule(String)`/`getModules()` and by `ServerAutoCrystal` etc.

- [ ] **Step 1: Write `PbModule`** (abstract base — each submodule extends this and implements `tick()`; unlike the old `ProxyModuleManager` approach, this does NOT reuse the client `Module` instance's tick logic — it is a standalone proxy-only class that only *reads* settings mirrored from the client module):

```java
package eu.client.pingbypass.modules;

import eu.client.settings.Setting;

import java.util.List;

/**
 * Base class for a proxy-side module implementation. Unlike the old ProxyModuleManager
 * approach (deleted — see plan Task 12), a PbModule does NOT share a Module instance
 * with the client: it is a standalone class that runs only on the proxy, reading settings
 * mirrored from the client via SyncModule. This removes the dual-execution race where the
 * same Module object could be ticked by both the real client process and the proxy process.
 */
public abstract class PbModule {
    private final String name;
    private boolean toggled = false;

    protected PbModule(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public boolean isToggled() { return toggled; }
    public void setToggled(boolean toggled) {
        this.toggled = toggled;
        if (toggled) onEnable(); else onDisable();
    }

    public void onEnable() {}
    public void onDisable() {}

    /** Called once per proxy tick while toggled. */
    public abstract void tick();

    public abstract List<Setting> getSettings();

    public Setting getSetting(String settingName) {
        return getSettings().stream()
                .filter(s -> s.getName().equalsIgnoreCase(settingName))
                .findFirst().orElse(null);
    }
}
```

- [ ] **Step 2: Write `SyncModule`** (one-directional mirror: client `Module` settings -> proxy `PbModule` settings, triggered by the existing `C2SSettingChangePacket`/`C2SModuleTogglePacket` — reuse those, do not invent new packets):

```java
package eu.client.pingbypass.modules;

import eu.client.settings.Setting;

/**
 * Mirrors a toggle/setting change from the client's real Module onto a proxy-side PbModule.
 * Called from PbPlayHandler when a C2SModuleTogglePacket/C2SSettingChangePacket arrives for
 * a module name that has a registered PbModule (see PbModuleManager).
 */
public class SyncModule {
    public static void applyToggle(PbModule module, boolean enabled) {
        module.setToggled(enabled);
    }

    public static void applySetting(PbModule module, String settingName, String rawValue) {
        Setting setting = module.getSetting(settingName);
        if (setting == null) return;
        setting.setValueFromString(rawValue);
    }
}
```

(If `Setting` has no `setValueFromString(String)` method, `grep -rn "class Setting" -A 30 src/main/java/eu/client/settings/Setting.java` first and adapt to whatever the existing `C2SSettingChangePacket` handler already uses to apply an incoming setting change — copy that exact mechanism rather than inventing a new one.)

- [ ] **Step 3: Write `PbModuleManager`**

```java
package eu.client.pingbypass.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry of proxy-side PbModule instances. Replaces ProxyModuleManager (deleted —
 * Task 12): instead of reusing client Module instances directly, each registered module
 * here is a standalone PbModule implementation (ServerAutoCrystal, ServerAutoTotem, ...).
 */
public class PbModuleManager {
    private final List<PbModule> modules = new ArrayList<>();

    public void register(PbModule module) {
        modules.add(module);
        modules.sort(Comparator.comparing(PbModule::getName));
    }

    public PbModule getModule(String name) {
        return modules.stream()
                .filter(m -> m.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public List<PbModule> getModules() {
        return modules;
    }

    public void tick() {
        for (PbModule module : modules) {
            if (module.isToggled()) module.tick();
        }
    }
}
```

- [ ] **Step 4: Test `PbModuleManager`** (follow `ProxyModuleManagerTest`'s style, but with a fake `PbModule` instead of a mocked client `Module`):

```java
package eu.client.pingbypass.modules;

import eu.client.settings.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PbModuleManagerTest {
    private PbModuleManager manager;
    private int fakeTickCount;

    private class FakeModule extends PbModule {
        FakeModule(String name) { super(name); }
        @Override public void tick() { fakeTickCount++; }
        @Override public List<Setting> getSettings() { return List.of(); }
    }

    @BeforeEach
    void setUp() {
        manager = new PbModuleManager();
        fakeTickCount = 0;
    }

    @Test
    void register_thenGetByName_caseInsensitive() {
        manager.register(new FakeModule("AutoCrystal"));
        assertNotNull(manager.getModule("autocrystal"));
    }

    @Test
    void getModules_sortedByName() {
        manager.register(new FakeModule("Surround"));
        manager.register(new FakeModule("AutoCrystal"));
        List<PbModule> modules = manager.getModules();
        assertEquals("AutoCrystal", modules.get(0).getName());
        assertEquals("Surround", modules.get(1).getName());
    }

    @Test
    void tick_onlyRunsToggledModules() {
        FakeModule module = new FakeModule("AutoCrystal");
        manager.register(module);
        manager.tick();
        assertEquals(0, fakeTickCount, "Untoggled module should not tick");

        module.setToggled(true);
        manager.tick();
        assertEquals(1, fakeTickCount);
    }

    @Test
    void unknownName_returnsNull() {
        assertNull(manager.getModule("DoesNotExist"));
    }
}
```

- [ ] **Step 5:** `./gradlew test --tests "eu.client.pingbypass.modules.PbModuleManagerTest"` — expect PASS.
- [ ] **Step 6: Commit**

```bash
git add src/main/java/eu/client/pingbypass/modules/PbModule.java src/main/java/eu/client/pingbypass/modules/SyncModule.java src/main/java/eu/client/pingbypass/modules/PbModuleManager.java src/test/java/eu/client/pingbypass/modules/PbModuleManagerTest.java
git commit -m "pingbypass: add PbModule/SyncModule/PbModuleManager framework"
```

---

## Task 8: `ServerAutoCrystal` — first submodule migration

**Files:**
- Create: `src/main/java/eu/client/pingbypass/modules/submodules/crystal/ServerAutoCrystal.java`
- Modify: `src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java` (register + tick it)

**Interfaces:**
- Consumes: current `AutoCrystalModule` logic at `src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java` — port the body of every method guarded by `shouldRunOnProxy()`/`isRunningOnProxy()` (lines 186, 222, 247, 277, 324, 333, 362, 424, 444, 450, 461 per the grep already run against this file) into `ServerAutoCrystal`, **written as if it were a normal client-side module running on the proxy's own `mc.player`/`mc.level`** — i.e. delete the `if (shouldRunOnProxy()) return;` / `if (isRunningOnProxy())` conditionals entirely rather than porting them; the code that used to run *inside* those conditionals becomes this class's unconditional logic.
- Produces: extends `PbModule` (Task 7), settings mirrored via `SyncModule` from the client `AutoCrystalModule`'s `place`/`break`/`placeRange`/`breakRange` (or whatever the actual field names are — read the file's setting declarations before writing this class) settings.

- [ ] **Step 1: Read the current file in full** (`Read src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java`) and list, next to each of the 11 grep-found line numbers, what the guarded block actually does (placement calc, attack timing, slot switch, cps logging, calc-time warning). This sub-step has no code output — it's the map you need before Step 2.
- [ ] **Step 2: Write `ServerAutoCrystal` class skeleton**, porting each identified block as an unconditional method, e.g.:

```java
package eu.client.pingbypass.modules.submodules.crystal;

import eu.client.pingbypass.modules.PbModule;
import eu.client.settings.Setting;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.IMinecraft;

import java.util.List;

/**
 * Proxy-only AutoCrystal: ported from AutoCrystalModule's isRunningOnProxy()-guarded
 * branches, with the guards removed — this class only ever runs on the proxy's own
 * LocalPlayer, so there is no second execution context to branch on.
 */
public class ServerAutoCrystal extends PbModule implements IMinecraft {
    public BooleanSetting place = new BooleanSetting("Place", "", true);
    public BooleanSetting attackBreak = new BooleanSetting("Break", "", true);
    public NumberSetting placeRange = new NumberSetting("PlaceRange", "", 4.5, 1.0, 6.0);
    public NumberSetting breakRange = new NumberSetting("BreakRange", "", 4.5, 1.0, 6.0);

    public ServerAutoCrystal() {
        super("AutoCrystal");
    }

    @Override
    public void tick() {
        // TODO(Step 2 continuation): port the unconditional body of each of the 11
        // guarded blocks from AutoCrystalModule here verbatim, in the same call order
        // the client module's own event subscriptions would have run them. Do this by
        // reading each block in place (Step 1's map) and moving it, not re-deriving it.
    }

    @Override
    public List<Setting> getSettings() {
        return List.of(place, attackBreak, placeRange, breakRange);
    }
}
```

  This skeleton intentionally leaves the tick-loop body as a TODO with an explicit pointer back to Step 1's map — the actual placement/attack/timing logic is ~150 lines currently spread across 6 methods in `AutoCrystalModule` and must be moved (not retyped from memory) to avoid silently changing behavior. Whoever executes this task must open the source file and move the real code before this task is considered done; leaving the TODO in place fails Step 4 below.

- [ ] **Step 3: Register and tick it.** In `ProxyServerTickListener.java`, instantiate `ServerAutoCrystal` once, register with a `PbModuleManager` instance (create one `EUClient.PB_MODULE_MANAGER` singleton if it doesn't exist yet, following the same declaration convention as `EUClient.PROXY_SERVER`), and call `EUClient.PB_MODULE_MANAGER.tick()` from the existing tick loop.
- [ ] **Step 4: Verify no TODO remains.** `grep -n "TODO(Step 2 continuation)" src/main/java/eu/client/pingbypass/modules/submodules/crystal/ServerAutoCrystal.java` must return nothing before this task is marked complete.
- [ ] **Step 5: Build + manual verification.** `./gradlew build`, build jar, copy to mods, `gradlew.bat runBoze`, connect through proxy, toggle AutoCrystal, confirm proxy places/breaks crystals with no `isRunningOnProxy()` branch anywhere in the new file (`grep -n "isRunningOnProxy\|shouldRunOnProxy" src/main/java/eu/client/pingbypass/modules/submodules/crystal/ServerAutoCrystal.java` must return nothing).
- [ ] **Step 6: Commit**

```bash
git add src/main/java/eu/client/pingbypass/modules/submodules/crystal/ServerAutoCrystal.java src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java src/main/java/eu/client/EUClient.java
git commit -m "pingbypass: add ServerAutoCrystal, proxy-only crystal logic with no proxy branching"
```

---

## Task 9: Strip proxy branches from `AutoCrystalModule`

**Files:**
- Modify: `src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java`

**Interfaces:** none — this is a pure deletion task, made possible by Task 8 now owning the proxy-side behavior.

- [ ] **Step 1:** Remove `proxyEnhanced = true` from the `@RegisterModule` annotation (line 50) — this module is client-only again.
- [ ] **Step 2:** For each of the 11 lines found by the earlier grep (`186, 222, 247, 277, 324, 333, 362, 424, 444, 450, 461`), delete the `if (shouldRunOnProxy()) return;` guard lines outright, and for the two `isRunningOnProxy()` blocks (222, 247, 461) delete the entire conditional block (that behavior now lives in `ServerAutoCrystal`).
- [ ] **Step 3:** `grep -n "isRunningOnProxy\|shouldRunOnProxy\|proxyEnhanced" src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java` — must return nothing.
- [ ] **Step 4: Build + manual verification.** `./gradlew build`, run locally without a proxy connection, confirm AutoCrystal still works exactly as a normal client-side module (this is the "no pingbypass" path — must be unaffected).
- [ ] **Step 5: Commit**

```bash
git add src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java
git commit -m "pingbypass: strip proxy branches from AutoCrystalModule, now client-only"
```

---

## Task 10: Repeat Tasks 8-9 for `AutoTotemModule` -> `ServerAutoTotem`

**Files:**
- Create: `src/main/java/eu/client/pingbypass/modules/submodules/totem/ServerAutoTotem.java`
- Modify: `src/main/java/eu/client/modules/impl/combat/AutoTotemModule.java`
- Modify: `src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java` (register second submodule)

**Interfaces:** identical shape to Task 8/9 — `ServerAutoTotem extends PbModule`, registered in the same `PbModuleManager`.

- [ ] **Step 1:** `grep -n "isRunningOnProxy\|shouldRunOnProxy\|proxyEnhanced" src/main/java/eu/client/modules/impl/combat/AutoTotemModule.java` — get the exact line list for this file (it will differ from AutoCrystal's).
- [ ] **Step 2:** Read the file, map each guarded block (this module is much smaller than AutoCrystal — research notes mention only a `Soft` setting, expect far fewer branches).
- [ ] **Step 3:** Write `ServerAutoTotem` following the exact same skeleton shape as `ServerAutoCrystal` (Task 8 Step 2) — `PbModule` subclass, unconditional ported logic, `getSettings()` mirroring `AutoTotemModule`'s actual settings.
- [ ] **Step 4:** Register in `ProxyServerTickListener` next to `ServerAutoCrystal`.
- [ ] **Step 5:** Strip `AutoTotemModule`'s proxy branches and `proxyEnhanced = true`, same as Task 9.
- [ ] **Step 6:** `grep -n "isRunningOnProxy\|shouldRunOnProxy\|proxyEnhanced" src/main/java/eu/client/modules/impl/combat/AutoTotemModule.java` — must return nothing.
- [ ] **Step 7: Build + manual verification** (same procedure as Task 8 Step 5, testing AutoTotem specifically).
- [ ] **Step 8: Commit** (both the new submodule and the stripped client module in one commit is fine here since Totem is small — follow the same message style as Tasks 8/9).

---

## Task 11: `SurroundModule` -> `ServerSurround`

**Files:**
- Create: `src/main/java/eu/client/pingbypass/modules/submodules/surround/ServerSurround.java`
- Modify: `src/main/java/eu/client/modules/impl/combat/SurroundModule.java`
- Modify: `src/main/java/eu/client/pingbypass/server/ProxyServerTickListener.java`

**Interfaces:** same shape as Tasks 8-10. Grep already found the 5 guard lines in this file: `76, 87, 95, 182, 222`.

- [ ] **Step 1:** Read `SurroundModule.java` in full (234 lines — small enough to read entirely in one pass) and map each of the 5 guarded blocks.
- [ ] **Step 2:** Write `ServerSurround extends PbModule`, porting the unconditional logic from each block (block-placement-at-feet calc, center-check, timing).
- [ ] **Step 3:** Register in `ProxyServerTickListener`.
- [ ] **Step 4:** Strip the 5 guards + `proxyEnhanced = true` from `SurroundModule.java`.
- [ ] **Step 5:** `grep -n "isRunningOnProxy\|shouldRunOnProxy\|proxyEnhanced" src/main/java/eu/client/modules/impl/combat/SurroundModule.java` — must return nothing.
- [ ] **Step 6: Build + manual verification** — confirm Surround still places blocks at feet client-side with no proxy connection, and via the proxy with a connection.
- [ ] **Step 7: Commit.**

---

## Task 12: `SpeedMineModule` -> raw-input-only (no submodule needed)

**Files:**
- Modify: `src/main/java/eu/client/modules/impl/player/SpeedMineModule.java`

**Interfaces:** none new — this task **deletes** proxy-awareness rather than moving it, because SpeedMine only needs input+angle (per the research doc's mining classification), which raw-input-forwarding (Tasks 3-6) already provides for free once the proxy's own `LocalPlayer` is the one mining.

- [ ] **Step 1:** Confirm the premise before deleting anything: with raw-input forwarding active, block-breaking key/mouse input is forwarded (Task 2's `ATTACK`/`USE` keys) and replayed on the proxy (Task 4), so the proxy's own vanilla mining code (unmodified) already runs `SpeedMineModule` exactly like a normal local module on the proxy's `LocalPlayer` — no `serverSend()`/`isProxyActive()` packet-forging is needed anymore, because the proxy is generating real digging packets itself via normal input, not via SpeedMine manually constructing `ServerboundPlayerActionPacket`s.
- [ ] **Step 2:** Remove `proxyEnhanced = true` from the `@RegisterModule` annotation (line 40).
- [ ] **Step 3:** Delete the `isProxyActive()` method (lines 498-499) and every call site using the grep list already gathered (`156, 163, 244, 260, 272, 297, 307, 560-899` range) — for each, delete the `if (shouldRunOnProxy()) return;` / `if (isProxyActive())` guard and, where the guarded branch built and sent a packet manually (`serverSend`/`serverSendSequenced`, lines 561-899), delete that manual packet-construction branch entirely — the vanilla mining code path (the branch that already runs for a normal local player) is what remains and is now correct for both client-only play and proxy-driven play.
- [ ] **Step 4:** Delete the now-unused `serverSend`/`serverSendSequenced` private methods (lines 560, 574) once their only call sites are gone.
- [ ] **Step 5:** `grep -n "isRunningOnProxy\|shouldRunOnProxy\|isProxyActive\|serverSend\|proxyEnhanced" src/main/java/eu/client/modules/impl/player/SpeedMineModule.java` — must return nothing.
- [ ] **Step 6: Build + manual verification.** `./gradlew build`, test SpeedMine both with no proxy (must still work exactly as before) and through the proxy with raw-input forwarding active (mining should now be driven by the proxy's own input replay, single source, no manual packet forging).
- [ ] **Step 7: Commit**

```bash
git add src/main/java/eu/client/modules/impl/player/SpeedMineModule.java
git commit -m "pingbypass: remove SpeedMine proxy packet-forging, raw-input replay covers it"
```

---

## Task 13: Delete `ProxyModuleManager` and its tests

**Files:**
- Delete: `src/main/java/eu/client/pingbypass/modules/ProxyModuleManager.java`
- Delete: `src/test/java/eu/client/pingbypass/modules/ProxyModuleManagerTest.java`
- Delete: `src/test/java/eu/client/pingbypass/modules/ProxyModuleIndependencePropertyTest.java`

**Interfaces:** none — superseded entirely by `PbModuleManager` (Task 7) + the 4 migrated submodules (Tasks 8, 10, 11) + SpeedMine's raw-input-only fix (Task 12), which together cover all 9 modules `ProxyModuleManager.PROXY_SAFE_MODULES` used to list (`AutoTotemModule, AutoCrystalModule, SurroundModule, AutoArmorModule, HoleFillModule, SelfFillModule, SelfTrapModule, AutoTrapModule, SpeedMineModule`).

- [ ] **Step 1: Check for scope gap.** `ProxyModuleManager` also listed `AutoArmorModule, HoleFillModule, SelfFillModule, SelfTrapModule, AutoTrapModule` — none of these are migrated by this plan (out of scope per Global Constraints). Confirm with the user before deleting whether those 5 modules should keep *some* proxy-side execution path (they'd lose proxy support entirely once `ProxyModuleManager` is gone, since nothing replaces its registration of them) — if the answer is "they can lose proxy support for now, re-add later, out of scope," proceed with Step 2; otherwise stop here and flag it back instead of silently regressing those 5 modules.
- [ ] **Step 2:** `grep -rn "ProxyModuleManager" src/main/java src/test/java` — confirm only the 3 files above reference it.
- [ ] **Step 3:** Delete all 3 files.
- [ ] **Step 4:** `./gradlew build` — full build including tests, must succeed with zero references remaining.
- [ ] **Step 5: Commit**

```bash
git add -A -- src/main/java/eu/client/pingbypass/modules/ProxyModuleManager.java src/test/java/eu/client/pingbypass/modules/ProxyModuleManagerTest.java src/test/java/eu/client/pingbypass/modules/ProxyModuleIndependencePropertyTest.java
git commit -m "pingbypass: remove ProxyModuleManager (shared-instance dual-execution architecture), superseded by PbModuleManager"
```

---

## Task 14: Remove the dumb-pipe rotation patch, now redundant

**Files:**
- Modify: `src/main/java/eu/client/pingbypass/PingBypassFlags.java`
- Modify: `src/main/java/eu/client/pingbypass/handler/PbPlayHandler.java`

**Interfaces:** none — pure deletion, safe now because raw-input forwarding means the proxy generates its own rotation from real turns (Task 4's `player.turn()`), so there is no second source needing reconciliation.

- [ ] **Step 1:** Remove `clientOnGround` and `clientHorizontalCollision` fields from `PingBypassFlags.java` (lines 46-47 and their doc comment 33-45).
- [ ] **Step 2:** `grep -n "clientOnGround\|clientHorizontalCollision" src/main/java/eu/client/pingbypass/handler/PbPlayHandler.java` — find and remove the yaw/pitch-override-on-forwarded-packet patch (the piggyback logic from `handleMovePlayer` described in the research doc's fix log) and any place that reads/sets these two flags.
- [ ] **Step 3:** `grep -rn "clientOnGround\|clientHorizontalCollision" src/main/java` — must return nothing repo-wide.
- [ ] **Step 4: Build + manual verification.** `./gradlew build`, then live-test moving + AutoCrystal/Surround together through the proxy — confirm no rubberbanding (this was the original symptom motivating the whole rewrite) and that this is now fixed at the root (single rotation source) rather than patched.
- [ ] **Step 5: Commit**

```bash
git add src/main/java/eu/client/pingbypass/PingBypassFlags.java src/main/java/eu/client/pingbypass/handler/PbPlayHandler.java
git commit -m "pingbypass: remove clientOnGround/clientHorizontalCollision rotation patch, superseded by single-source raw-input rotation"
```

---

## Self-Review Notes

- **Spec coverage:** All 7 steps from `PINGBYPASS_RESEARCH.md` section 5 are covered: Task 1-6 = steps 1-2 (input infra + movement cancellation), Task 7 = step 3 (framework), Task 8-9 = step 4-5 (AutoCrystal first), Task 10-12 = step 6 (restricted scope: totem, surround, speedmine only, per user's explicit narrowing), Task 13 = cleanup of the pre-existing conflicting `ProxyModuleManager`, Task 14 = step 7 (remove yaw/pitch/onGround patch).
- **Placeholder scan:** The only intentional TODO is in Task 8 Step 2, and Task 8 Step 4 makes it a hard gate (`grep` must be empty) before the task is considered done — not a silent placeholder.
- **Type consistency:** `PbModule`/`PbModuleManager`/`SyncModule` signatures introduced in Task 7 are reused identically in Tasks 8, 10, 11 (`extends PbModule`, `getSettings()` returning `List<Setting>`, registered via the same `EUClient.PB_MODULE_MANAGER`). `C2SInputKeyPacket.Key`/`Action` enums from Task 2 are the exact types consumed in Tasks 3, 4, 5.
- **Known open question surfaced, not silently resolved:** Task 13 Step 1 explicitly flags that 5 modules (`AutoArmorModule`, `HoleFillModule`, `SelfFillModule`, `SelfTrapModule`, `AutoTrapModule`) lose their only proxy execution path once `ProxyModuleManager` is deleted, since this plan's scope (per user instruction) doesn't migrate them. This is a real regression risk worth a decision, not something to bury.
