# Session 2026-08-13 — AutoCrystal CPS + Silent Flicker Investigation (Opus 5 background agent)

Branch: `session-2026-08-13-whitelist-gui-and-ports`. All fixes below are **compiled, NOT tested in-game**
unless stated otherwise. Continue from here tomorrow.

## Background — fixed BEFORE this doc's bugs (already tested-pending, prior in this session)

- `SurroundModule`/`SelfTrapModule` reactive crystal-attack path was attacking `crystal.getId()`
  (a fresh client-local `new EndCrystal(...)`'s own locally-counted id) instead of `packet.getId()`
  (the real server-assigned id) — silent no-op regardless of ping.
- All 3 attack-crystal call sites (`SurroundModule.attackThreateningCrystal`,
  `SelfTrapModule.attackThreateningCrystal`, `WorldUtils.destroyCrystals`) sent a fake
  `packetRotate()` immediately before `ServerboundAttackPacket`. Removed — reference: Shoreline's
  `SurroundModule` (github.com/ImLegiitXD/shoreline) sends no rotation before a crystal attack;
  attack-reach check is pure distance, not look-angle, so faking a rotation only risks tripping
  Grim's rotation checks (e.g. RotationGS) for zero benefit.

---

## (A) AutoCrystal Rotate-mode CPS regression — **NOT FIXED**. One partial fix (noise removal) shipped; the main gap is still open and needs the user to gather data with the new instrumentation before it can be root-caused.

**User's isolated test case:** same server, same spot, same target. AutoCrystal Rotate mode
(Normal/Packet/MovementSync — ANY mode that rotates) on native protocol → capped 3-7cps.
`Rotate=None` (the only mode that never rotates) → ~7cps. ViaFabricPlus downgraded to 1.20.x →
stable 10-11cps **regardless of rotate mode or facing direction at start**.

### Root cause #1 (partial — explains "every rotate mode slower than None") — fix applied, compiled, untested

`RotationUtils.getRotations()` added `±2°` uniform random noise to every computed rotation
(`src/main/java/eu/client/utils/rotations/RotationUtils.java`). Legacy eu-client code, no setting,
no comment, no mechanism behind it. Deleted (1-line diff + explanatory comment left in place).

Why this explains "every rotate mode is slow, None is fast, 1.20.x is unaffected":
1. **Broke `RotationManager.packetRotate`'s dedup.** That method opens with
   `if (serverYaw == yaw && serverPitch == pitch) return;`. With fresh noise every call, that
   comparison is never true — a standing-still player attacking the same crystal position fired a
   brand new `ServerboundMovePlayerPacket.Rot` on **every single attack/place** instead of zero
   packets. On 1.21.2+ the client also delimits ticks with `ServerboundClientTickEndPacket`, so
   those extra rotation packets land inside a tick the server can see is already accounted for —
   on a 1.20.x connection (ViaFabricPlus strips that marker) they're indistinguishable from normal
   aim, hence "unaffected at 1.20.x".
2. **Yaw didn't land on the client's sensitivity grid.** Vanilla yaw only ever moves in
   mouse-sensitivity-derived increments; uniform noise doesn't — exactly the signal rotation
   checks (RotationGS-style) key off.

Confirmed against Shoreline: `RotationUtil.getRotationTo()` returns exact angles, no noise.

**Test to run:** Rotate=Packet, native protocol, same spot/target — measure CPS before vs after
this fix. If it climbs to ~None's ~7cps, this part of the theory is confirmed.

### Root cause #2 — residual gap (None ~7cps vs 1.20.x ~10-11cps) — **NOT FOUND, blocked**

**Falsified theory:** "Instant fast-path (`onEntitySpawn`) loses its edge on native because it
fires after that tick's `ClientTickEndPacket` marker, so the server/anticheat attributes it to the
next tick." — **User tested this: turned OFF Instant on both native and 1.20.x. CPS gap
persisted.** Theory is dead, do not revisit without new evidence.

**Ruled out by source reading** (do not re-check these without new evidence):
- No protocol-version branch anywhere in the crystal place/attack pipeline (grepped repo-wide).
- Event ordering is correct: `PlayerUpdateEvent` (before `super.tick()`) → `UpdateMovementEvent`
  (after) → `sendPosition()` → `UpdateMovementEvent.Post`. AutoCrystal's attack/place runnable
  runs at `UpdateMovementEvent.Post` — after that tick's movement packet, before
  `ServerboundClientTickEndPacket` (sent at the end of `Minecraft.tick()`, after
  `LocalPlayer.tick()`). No tick-marker skew for the normal (non-instant) path.
- `sendSequencedPacket`/`BlockStatePredictionHandler` adds no round trip:
  `ClientboundBlockChangedAckPacket` exists since 1.19 (present in 1.20.x too, not a 1.21.2+
  addition), and crystal placement doesn't write any predicted block state, so the prediction
  manager is empty and gates nothing.
- No mixin defers/queues/batches outbound packets (`ClientConnectionMixin` only posts an event +
  logs).
- The gap itself: ~7cps vs ~11cps ≈ 143ms vs 91ms per cycle ≈ **exactly 1 tick** (50ms) of
  difference — but nothing in the repo adds exactly one tick anywhere in this path.

**What's left is outside the repo** (ViaFabricPlus's own repacking, server-side entity-tracker
broadcast timing, anticheat-side reach/rate checks) — cannot be read from source, needs packet
capture or in-game instrumentation.

**Instrumentation already shipped** (1 file, reuses the existing `Counter` utility, no new
files/modules): `AutoCrystalModule.getMetaData()` now prints
`calcTime, calcCount, calcDamage, <sentPlaces>/<sentAttacks>-><confirmedCPS>`. The last number is
the old `crystalsPerSecond` (crystal spawns the server actually confirmed); the two new numbers
are **packets actually sent** client-side in the same 1s window.

**Next test (this is the thing that unblocks (A)):** run 10s on native and 10s on 1.20.x, read the
HUD module list, compare `sentPlaces` between the two:
- If `sentPlaces` is roughly equal (~15-20/s) on both, but `confirmedCPS` differs → loss is on the
  server/anticheat side, outside this repo, needs a packet capture (Wireshark or similar) to go
  further.
- If `sentPlaces` itself drops on native → the gate is client-side after all, and there's a
  specific early-fail condition in `placeCrystals` still to find — worth another pass.

**Secondary asymmetry found, deliberately left alone:** `onEntitySpawn` (~line 427) only calls
`packetRotate` for `Rotate=Packet` mode, while `onDestroyBlock` (~line 501) calls it for every
mode except `None` — inconsistent, but since more rotation = slower per the user's data, "fixing"
this asymmetry by adding more `packetRotate` calls would make things worse. Left as-is.

---

## (B) Surround + AutoCrystal — slot duplicate/swap (also happens AT 1.20.x, and display desync without downgrade)

**User's report (from screenshots):**
1. Silent block-place flicker persists even when downgraded to 1.20.x — not a 1.21.2+-only
   echo-packet thing.
2. Holding a golden apple, Surround+AutoCrystal both on → 3 items' slots duplicate/jumble.
3. Holding an End Crystal directly, Surround+AutoCrystal both on → 2 items' slots continuously
   swap back and forth.
4. Separately, WITHOUT the 1.20.x downgrade: a slot holding a crystal sometimes visibly renders as
   sword/golden apple (whatever's actually in hand) instead of staying as crystal — display desync.

### Root cause — FIXED, compiled, untested. Two bugs, both protocol-independent (explains why downgrading doesn't fix it):

1. **Silent switch runs on the netty IO thread.** `SurroundModule.onPacketReceive` /
   `SelfTrapModule.onPacketReceive` (the `crystalDestruction`/reactive fast-path branches) call
   `InventoryUtils.switchSlot`/`switchBack` directly from the netty thread —
   `ClientConnectionMixin.channelRead0` posts `PacketReceiveEvent` before vanilla hands off to the
   main thread. Mode `Normal` writes `mc.player.getInventory().setSelectedSlot()` directly; modes
   `AltSwap`/`AltPickup` replay a container click via `mc.gameMode.handleContainerInput()` — both
   **mutate client inventory state concurrently with the main thread ticking/rendering**. This is
   the mechanism behind "3 items duplicate/jumble".
2. **`previousSlot` was the wrong restore target.** Every caller computed
   `previousSlot = mc.player.getInventory().getSelectedSlot()` — the **client's** currently-shown
   slot, which a silent switch never touches. This is only correct when exactly one module holds
   a silent switch open at a time. Surround holds its switch open across a burst of placements;
   AutoCrystal interleaves ~10 places/sec in between, and each of AutoCrystal's own restores sets
   the **server-visible** slot back to whatever AutoCrystal's own `previousSlot` was — yanking the
   server-side held item out from under whatever Surround was mid-use with. Then Surround's own
   `switchBack` restores to its own (now-stale) `previousSlot`. The two sides trade the slot back
   and forth — this is symptom #3 (2 items swapping continuously) and, with a third item in the
   mix (golden apple), symptom #2.

Symptom #4 (crystal slot displaying as sword/apple without downgrading) is believed to trace back
to root cause #1 (off-thread `setSelectedSlot`/container-click mutating client state the render
thread is reading mid-frame) — **not confirmed with certainty**, needs in-game re-test after
building.

### Fix (3 files changed, `git diff --stat`: +80/-9)

- `src/main/java/eu/client/utils/minecraft/InventoryUtils.java`:
  - New `SILENT_RESTORE` map: `slot -> server-side slot at the moment the switch was opened`
    (read from `POSITION_MANAGER.getServerSlot()`). `switchBack` now restores to that captured
    value instead of the stale client `previousSlot`. This means AutoCrystal's restore correctly
    hands the server slot back to whatever SpeedMine/Surround actually had it on, instead of
    stomping it.
  - New guard: `if (!mc.isSameThread())` — off-thread calls (i.e. from `onPacketReceive`) degrade
    `Normal` mode down to packet-only Silent (same server-visible effect, doesn't touch client
    inventory state, no extra latency cost) and reject `Alt*` modes outright if the target slot is
    outside the hotbar (can't be represented by a held-slot packet alone).
  - `switchSlot` signature changed to return `boolean` (false = the switch was rejected by the
    new guard).
- `SurroundModule` (2 call sites) + `SelfTrapModule` (1 call site) in `onPacketReceive`: now
  `if (!InventoryUtils.switchSlot(...)) return;` — better to skip one reactive attack attempt than
  place/attack with the wrong item selected. The tick-polled `attackThreateningCrystal()` path
  still covers the miss on the next tick.

## (C) AutoCrystal + SpeedMine flicker (specifically WHILE ViaFabricPlus is downgraded to 1.20.x)

**Confirmed: same root cause #2 as (B) above, not a separate bug.** `SpeedMineModule`
(Strict/DoubleMine) deliberately delays its own `switchBack` by 100ms (`switchAction` → `onTick`).
Within that 100ms window AutoCrystal places 1-2 crystals; each place's restore sets the
server-visible slot back to AutoCrystal's own previous slot, stealing SpeedMine's tool out from
under it. 100ms later SpeedMine restores to its own now-stale slot. Fixed by the same
`SILENT_RESTORE` change above — no separate patch needed.

---

## Overall status as of end of session

- `gradlew.bat compileJava` passes clean with all of the above applied.
- **Nothing in this document has been tested in-game yet.**
- Suggested test order for tomorrow:
  1. Read `sentPlaces/sentAttacks->confirmedCPS` off the AutoCrystal HUD line in both native and
     1.20.x-downgraded sessions, 10s each, same spot — this is what unblocks (A)'s residual gap.
  2. Rotate=Packet, native protocol, before/after the noise-removal commit — confirms/denies the
     noise theory in isolation.
  3. Re-test Surround+AutoCrystal (golden apple in hand, then crystal in hand) and
     AutoCrystal+SpeedMine combos for the duplicate/swap/flicker symptoms.
  4. Re-check symptom #4 (crystal slot rendering as sword/apple without downgrading) specifically,
     since that one's root cause isn't fully confirmed.

## Also done this session (unrelated to A/B/C, same branch)

- `PhaseModule` (`src/main/java/eu/client/modules/impl/movement/PhaseModule.java`) was fully
  replaced with a clean port of example-addon-master's `PearlPhase` mechanism (corner/boundary
  target math + physics-solved throw pitch, replacing the old fixed-pitch/Teleport-mode version).
  Compiled, untested in-game.
- `SpeedMineModule`: added an `AntiCrawl` toggle (nested under `Double`, default on) gating the
  existing crawl-escape logic (mine the block above/below your feet to stand back up when
  `isVisuallyCrawling()`), so it can be turned off independently.
- **Fixed, compiled, untested:** occasionally, right when SpeedMine starts targeting a new block,
  eating (golden apple) got blocked. Root cause: `Action`'s constructor (`tryStart()` →
  `legacyStart()`) sends its pickaxe-switch `ServerboundSetCarriedItemPacket` from the main tick
  thread, while `PbPlayHandler.syncSlotForInteract()` sends its own food-slot correction +
  sets `interactPaused` from the proxy's netty IO thread (handling the real client's connection).
  `canStartNow()`'s `interactPaused` check alone doesn't prevent the two independent packet sends
  from interleaving on the wire in either order — occasionally the pickaxe switch landed AFTER
  the food switch (and even after the eat's `ServerboundUseItemPacket`), silently failing the eat.
  Fix: added `SpeedMineModule.interactSyncLock`, synchronized around `Action.tryStart()`'s
  check-and-send and around `syncSlotForInteract()`'s read-and-send, so the two can no longer
  interleave. Files: `SpeedMineModule.java`, `PbPlayHandler.java`.
