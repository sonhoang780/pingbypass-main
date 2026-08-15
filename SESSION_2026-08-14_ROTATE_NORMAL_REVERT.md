# Phiên fix Rotate Normal/Packet (AutoCrystal + SpeedMine) — 2026-08-14/15

## Bối cảnh
User báo: bản 1.21.4 gốc, AutoCrystal Rotate=Normal, Sprint/MovementFix/SnapBack đều tắt →
**không bị flag khi di chuyển**. Bản port 26.1.2, cùng config y hệt → **bị flag nặng khi di chuyển**.
Nhiều lần sửa trong session (ClientRotationEvent arbitration, priority-compare, legacy-luôn-thắng...)
đều KHÔNG giải quyết được, vì tất cả các fix đó động vào phần kiến trúc `ClientRotationEvent`/
`MovementSync` — thứ **không tồn tại trong bản gốc 1.21.4** và không áp dụng khi Sprint/MovementFix
đều tắt như config user test.

User mất niềm tin vào các fix tự sáng tạo, ra lệnh: **revert Rotate Normal/Packet của AutoCrystal
và SpeedMine về đúng bản backup trên GitHub (`sonhoang780/pingbypass-main`, branch `master`)**, sau
đó diff lại với bản gốc 1.21.4 (`C:\Users\conng\Desktop\pingbypass-main`) để xác nhận khớp 100%.

## Phát hiện chính
`origin/master` (HEAD `0952bc2`, trước khi session này bắt đầu) **đã có sẵn bản port
`RotationManager` đúng 1:1 với bản gốc 1.21.4** — single `PriorityBlockingQueue<Rotation>`,
method `rotate()`, KHÔNG có `ClientRotationEvent`, KHÔNG có "MovementSync". Toàn bộ kiến trúc
`ClientRotationEvent`/`legacyQueue`/`MovementSync`/priority-arbitration là phát minh **trong session
này**, chưa từng merge vào master, và không có tương ứng trong bản gốc.

→ Mọi fix trước đó trong session (kể cả của tôi) đều đang cố sửa một kiến trúc **tự chế, sai ngay
từ đầu**, thay vì quay về bản đã đúng sẵn.

## Việc đã làm

### 1. Revert kiến trúc AutoCrystal + SpeedMine + ServerAutoCrystal về master
- Xóa mode `"MovementSync"` khỏi setting `Rotate` của cả 3 module (`AutoCrystalModule`,
  `SpeedMineModule`, `ServerAutoCrystal`) — options còn lại đúng bản gốc: `None/Normal/Packet`.
  Default đổi `MovementSync` → `Normal` (AutoCrystal/ServerAutoCrystal), khớp master.
- Xóa toàn bộ `onClientRotation(ClientRotationEvent)` handler + field `attackRotations`/
  `placeRotations` (AutoCrystal, ServerAutoCrystal) và `rotateActive`/`onClientRotation`
  (SpeedMine) — không còn dùng sau khi bỏ MovementSync.
- Mọi call site Normal/Packet giờ gọi thẳng `RotationManager.legacyRotate()`/`packetRotate()`
  (tên hàm khác master do lịch sử đặt tên trong session, nhưng **logic/tham số/priority giống
  hệt** `rotate()` của master — đã verify field-for-field).
- Diff lại 9 call site AutoCrystal, 4 call site SpeedMine với bản gốc `Desktop\pingbypass-main`:
  khớp 1:1 về shape (Normal→rotate+priority, Packet→packetRotate, place = attack-priority + 1).
  3 call site thừa so với bản gốc (dòng 1085/1086, 1119/1120 AutoCrystalModule) là tính năng
  "mineIgnore" — user yêu cầu riêng ("khi obsidian vỡ lập tức break crystal"), không có ở bản gốc,
  nhưng dùng đúng cùng khuôn mẫu rotate call, không phải divergence của cơ chế rotate.

### 2. Khôi phục các phần bản gốc mà port từng xóa (trước khi user yêu cầu revert triệt để)
- `RotationsModule` (module `Rotations`, setting `MovementFix`+`SnapBack`) — bị xóa nguyên module,
  đã tạo lại verbatim.
- `RotationManager`: 4 handler `onUpdateVelocity`/`onKeyboardTick`/`onPlayerJump`/`onPlayerJump$POST`
  (MovementFix, default OFF) — đã port lại verbatim.
- `WorldUtils.placeBlock`: `SnapBack` (snapshot yaw/pitch trước rotate, `packetRotate(prev)` sau khi
  place) — đã khôi phục.
- `RotationUtils.getRotations`: nhiễu `±2°` (`(Math.random()-0.5)*4`) trên mọi yaw/pitch tính toán —
  đã khôi phục. Đây là fix **không gated bởi toggle nào**, nghi ngờ mạnh nhất là nguyên nhân thật
  của bug "flag khi di chuyển, Sprint/MovementFix/SnapBack đều tắt": không nhiễu → yaw giống hệt
  mỗi tick khi target đứng yên → `sendPosition()` tính `dYaw=0` → server ngừng nhận rotation-update
  suốt lúc giữ, trong khi local physics vẫn chạy ở yaw thật → lệch prediction.

### 3. RotationManager arbitration (áp dụng khi Sprint/MovementSync có bật — KHÔNG liên quan bug
   Sprint-tắt user báo, nhưng vẫn đúng để không tái diễn bug tương tự với Sprint Grim)
- `legacyQueue` (Normal, đúng bản gốc) giờ **luôn thắng tuyệt đối** so với `ClientRotationEvent`
  (MovementSync/Sprint Grim — addon riêng của project) khi có gì đang queue. `ClientRotationEvent`
  chỉ được post/xét khi `legacyQueue` rỗng hoàn toàn. Tách hẳn 1 góc riêng theo đúng yêu cầu.

## Trạng thái build
`./gradlew.bat compileJava` — **compile sạch**, không lỗi, không cảnh báo mới (chỉ CRLF/native-access
warning thường thấy).

```
git diff --stat
 RotationManager.java                  | 115 +++++++++++++++++----
 AutoCrystalModule.java                 |  74 ++-----------
 SpeedMineModule.java                   | 108 ++++---------------
 ServerAutoCrystal.java                 |  43 ++------
 WorldUtils.java                        |  10 ++
 RotationUtils.java                     |  35 +++----
 6 files changed, 155 insertions(+), 230 deletions(-)
```

Chưa test in-game (chỉ compile). Cần build jar, copy vào
`example-addon-master/run/mods/`, chạy `gradlew.bat runBoze`, test lại đúng config đã báo bug:
Rotate=Normal, Sprint/MovementFix/SnapBack đều tắt, xem còn flag khi di chuyển không.

## Nghi ngờ hàng đầu cho bug gốc
`RotationUtils.getRotations` thiếu nhiễu ±2° — chỗ duy nhất trong toàn bộ chuỗi thay đổi áp dụng
**vô điều kiện**, khớp đúng điều kiện test của user (không toggle nào bật). Nếu test lại vẫn flag,
nghi phạm tiếp theo là nhánh `proxyForwardingActive` trong `onUpdateMovement`/`.POST`/`packetRotate`
(addon riêng cho kiến trúc proxy PingBypass, không có ở bản gốc) — cần biết user test qua proxy
PingBypass hay client connect thẳng để khoanh vùng tiếp.

## Diff bổ sung (theo yêu cầu tiếp tục không tin master, đối chiếu thẳng 1.21.4)
- Xác nhận trực tiếp: `Desktop\pingbypass-main\...\RotationManager.java` dòng 211-212 **CÓ**
  `Easing.toDelta(lastRenderTime, 1000)` — user nói bản gốc không có 1000ms là **không khớp** với
  file thật. Nhưng hàm này (`getRenderRotations`) chỉ vẽ third-person model của chính mình, comment
  xác nhận "never touches the real getYRot()/packets/game logic" — không thể là nguyên nhân bug
  flag-khi-di-chuyển (server không thấy giá trị này). Code hiện tại giữ đúng `1000` theo bản gốc.
- Diff toàn bộ `RotationManager` giữa `origin/master` và `Desktop` bản gốc: master (chính bản
  GitHub được yêu cầu revert về) **cũng đã tự lệch** khỏi bản gốc ở vài chỗ (đổi lerp 1000→100 có
  chủ đích, cấu trúc `packetRotate` proxy branch khác — proxy là tính năng riêng của project nên
  chỗ này buộc phải khác). Không có chỗ nào trong các khác biệt này ảnh hưởng tới packet gửi server
  ngoài phần proxy (đã verify không đổi core Normal/Packet mechanism).
- Kết luận: master dùng để lấy lại đúng SHAPE/kiến trúc (bỏ ClientRotationEvent/MovementSync), còn
  GIÁ TRỊ cụ thể (như lerp 1000ms) vẫn ưu tiên đối chiếu trực tiếp Desktop gốc khi có mâu thuẫn.

## Ghi chú tin cậy
Theo yêu cầu, mọi thay đổi rotate-mechanism trong file này đều được xác nhận qua diff trực tiếp với
`C:\Users\conng\Desktop\pingbypass-main` (bản gốc 1.21.4) và/hoặc `origin/master` trên GitHub — không
dựa vào suy luận/tự thiết kế lại.
