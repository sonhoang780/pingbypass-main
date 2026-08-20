# Follower module — design notes (in progress, brainstorm not finished)

Status: paused mid-brainstorm to fix SpeedMine/ESP/KillAura/AutoPot bugs. Resume from here.

## Confirmed decisions

1. **Follower drives ElytraFlyModule directly** (Option A, recommended, user-approved):
   Follower toggles/steers `ElytraFlyModule.mode` (Control/ControlRocket) itself to chase —
   not a standalone flight implementation, not just reusing rotation/lock-on patterns.

2. **Target selection: shares whatever target another combat module already has locked**
   (AutoCrystal/KillAura/etc.) — Follower does NOT pick its own target independently, and does
   NOT do simple nearest-enemy-in-range selection. It rides on the existing target of whichever
   combat module is currently active.

## Still open (never answered)

- **"Giữ khoảng cách an toàn mỗi khi enemy dùng elytra để chạy"** — exact semantics undecided.
  Two options floated, neither picked:
  - (a) When target `isFallFlying()`, Follower backs off to a larger safe distance instead of
    closing in (avoid crashing into terrain/overcommitting while they're escaping by elytra).
  - (b) Stop following entirely while target is fall-flying, resume only once they land.
  User deferred this ("để sau") to prioritize SpeedMine fixes — pick up this question first
  when resuming.
- No design presented yet for: how Follower picks WHICH combat module's target to ride on if
  multiple are active simultaneously; how it decides when to engage ElytraFlyModule vs. just
  walk/sprint; safe-distance number/setting; whether it fights back itself or purely repositions.

## Next steps when resuming

1. Ask the deferred safe-distance question (a vs b above, or something else).
2. Cover: which module's target takes priority if several are locked at once.
3. Present short in-chat design (this is architectural scope per the brainstorming skill
   classification already applied — new module, no existing flow to extend) covering:
   architecture, ElytraFlyModule hook points, target-source arbitration, safe-distance logic,
   settings surface.
4. Get approval, then write full spec doc (this file becomes that, once complete), then
   superpowers:writing-plans for implementation.
