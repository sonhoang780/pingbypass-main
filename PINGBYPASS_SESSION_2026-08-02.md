# PingBypass Session Log — 2026-08-02

## Tóm tắt trong ngày

Bắt đầu từ kế hoạch `PINGBYPASS_RESEARCH.md` (viết hôm 2026-08-01): rewrite kiến trúc pingbypass từ `proxyEnhanced`/`shouldRunOnProxy()` rải rác sang tổ chức kiểu 3arthh4ck (submodule proxy riêng). Thực hiện 14 task theo plan (`docs/superpowers/plans/2026-08-02-pingbypass-raw-input-rewrite.md`), sau đó phát hiện premise nền tảng SAI (raw-input-forwarding không cần thiết như tưởng), phải revert lớn, rồi tìm ra cơ chế THẬT qua research trực tiếp source code 3arthh4ck/PingBypass thật (clone qua `gh`).

## Trạng thái code hiện tại (khi ghi file này)

- Đã build+test pass (`./gradlew build`).
- Đã revert Task 1-6 (raw-input pipeline: `ClientInputService`/`ServerInputService`/`C2SInputKeyPacket` v.v.) về trạng thái checkpoint gốc — **ĐANG SỬA LẠI LẦN NỮA** theo phát hiện mới nhất (xem "Cơ chế thật" bên dưới), CHƯA XONG khi ghi file.
- Còn giữ (không revert, vẫn đúng):
  - Task 7: `PbModule`/`SyncModule`/`PbModuleManager` framework (`eu.client.pingbypass.modules`).
  - Task 8-11 + AutoTrap: `ServerAutoCrystal`/`ServerAutoTotem`/`ServerSurround`/`ServerAutoTrap` (`eu.client.pingbypass.modules.submodules.*`) — client module (AutoCrystalModule/AutoTotemModule/SurroundModule/AutoTrapModule) mất `proxyEnhanced`, guard bằng `PingBypassFlags.isPingBypassActive()` (client bỏ qua tính toán cục bộ khi đang connect qua proxy, để `ServerXxx` bên proxy tự lo — khớp `isPingBypass()` thật của earthhack).
  - Task 12 phần fix bug thật: `SpeedMineModule`'s render-interpolation (`proxyPrimaryUpdateTime`/`Interval` epoch bug) — **giữ nguyên fix**, nhưng đã revert `isProxyActive()`/`serverSend()`/`serverSendSequenced()` về bản gốc (không xóa nữa, đây là cơ chế ĐÚNG matching earthhack's `ClientDiggingService`-style).
  - Task 13: xóa `ProxyModuleManager` (code chết xác nhận — không ai gọi `protocolHandler.handle()`) — vẫn đúng, giữ nguyên.
  - Task 14 phần rotation patch (`clientOnGround`/`clientHorizontalCollision`) — **ĐÃ REVERT về bản gốc** (patch piggyback cũ trong `PbPlayHandler.handleMovePlayer` cần thiết lại).

## Phát hiện quan trọng nhất — cơ chế THẬT (đã trace tới call site)

Clone trực tiếp `3arthqu4ke/3arthh4ck` (nhánh `main`, bản đầy đủ có VPS/HeadlessMC, KHÔNG phải bản standalone `3arthqu4ke/pingbypass` cũ đã deprecated) vào `/tmp/3arthh4ck-ref` (đã xóa khi session kết thúc, cần re-clone nếu muốn xem lại: `gh repo clone 3arthqu4ke/3arthh4ck /tmp/3arthh4ck-ref -- --depth 1`).

**`PbNetHandler.processPlayer(CPacketPlayer)`** (proxy nhận packet movement client gửi):
```java
C2SActualPos actual = PingBypass.PACKET_SERVICE.getActualPos();
if (actual == null || !actual.isValid(packetIn)) {
    this.handle(packetIn);  // dumb-pipe fallback, CHỈ khi desync
    return;
}
double x = packetIn.getX(...), y = ..., yaw = ...;
MotionUpdateHelper.makeMotionUpdate(x, y, z, yaw, pit, onGround, ...);
```

**`MotionUpdateHelper.makeMotionUpdate()`**:
1. Bắn `MotionUpdateEvent` (module như `ListenerRotations` của `ServerAutoCrystal` chèn yaw/pitch ở đây — điểm hook DUY NHẤT, đảm bảo trước khi gửi).
2. `setPosition(...)` — set lại x/y/z/yaw/pitch lên `mc.player` (proxy's own player) — giá trị này CÓ THỂ bị module ghi đè.
3. `PingBypass.PACKET_MANAGER.allowAllOnThisThread(true)` rồi gọi **`invokeUpdateWalkingPlayer()`** — proxy TỰ build+gửi packet MỚI qua connection thật của chính nó tới server thật.

**`Pb2SManager`**: blacklist mặc định CANCEL các packet C2S nhạy cảm (`CPacketPlayer`/`CPacketInput`/`CPacketPlayerAbilities`/...) trừ khi được `authorize()` — chỉ `MotionUpdateHelper` (qua `allowAllOnThisThread`) và vài nơi cụ thể khác (`WindowClickService.authorize()`) được phép bypass.

**Kết luận cơ chế thật (đã confirm, độ tin cậy cao vì trace tới tận call site, không suy diễn)**:
- Client **vẫn tự chơi bình thường** (di chuyển/tấn công/đào — luôn tính toán + gửi packet thật, `CPacketPlayerService` xác nhận không cancel gì, chỉ gửi thêm 1 packet nhẹ `C2SActualPos` để proxy so sánh desync).
- Proxy **KHÔNG forward thẳng packet di chuyển của client** (trừ fallback khi desync) — mà lấy TỌA ĐỘ/GÓC NHÌN đã tính sẵn từ packet đó, feed vào player của CHÍNH PROXY, cho module chèn/ghi đè (rotation aim-lock...), rồi **tự sinh packet mới** gửi qua connection thật của proxy tới server.
- **KHÁC** với thiết kế `ServerInputService`/`KeyMapping` tôi tự làm ban đầu (replay phím thô WASD để proxy tự chạy physics ĐỘC LẬP) — earthhack đơn giản hơn nhiều: chỉ copy tọa độ ĐÃ TÍNH SẴN từ client (client tự chạy physics của chính nó, terrain collision etc luôn đúng theo góc nhìn client), không tự chạy physics riêng trên proxy.

## Cập nhật — cơ chế mới ĐÃ IMPLEMENT (cùng phiên, sau khi ghi mục trên)

Đã sửa xong theo đúng cơ chế `MotionUpdateHelper`, build + test pass:

1. **`ClientPlayerEntityMixin.sendMovementPackets`** (wrap `LocalPlayer.sendPosition`): bỏ hẳn nhánh cancel-khi-là-proxy. Giờ CẢ CLIENT LẪN PROXY chạy chung 1 code path — trên proxy, `mc.player` (ghost) được `PbPlayHandler.handleMovePlayer` set lại tọa độ mỗi khi client gửi packet, rồi `sendPosition()` tick tiếp theo của CHÍNH proxy tự build+gửi packet MỚI tới server thật (`mc.getConnection()` trên proxy chính là kết nối server thật — xác nhận qua `ProxyServer.setServerConnection` các call site đều gán `mc.getConnection().getConnection()`). Đây chính là tương đương `invokeUpdateWalkingPlayer()`.
2. **`PbPlayHandler.handleMovePlayer`**: bỏ `forward(p)` (không dumb-pipe packet di chuyển của client nữa) và bỏ đoạn piggyback-rewrite rotation thủ công (không cần nữa) — chỉ còn mirror x/y/z/yaw/pitch/onGround lên `mc.player`.
3. **`RotationManager`**: bỏ hết nhánh đặc biệt cho proxy trong `onUpdateMovement`/`onUpdateMovement$POST`/`packetRotate` — giờ dùng chung 1 code path cho cả 2 phía (vì `mc.getConnection()` đã tự động đúng đích ở cả 2 phía). Điểm hook cho module rotation (AutoCrystal/AutoTotem/Surround/AutoTrap qua `RotationManager.rotate()`) vẫn hoạt động bình thường — được `sendPosition`'s `WrapOperation` (đọc `ROTATION_MANAGER.getRotation()`) áp dụng trước khi gửi, đúng vai trò `MotionUpdateEvent`.
4. `./gradlew compileJava` và `./gradlew test` đều pass.

## Việc CÒN LẠI (chưa làm)

1. **Chưa deploy/test live trên VPS** — cần chạy `deploy-jar.ps1`, connect thử, xác nhận di chuyển/đập block hoạt động bình thường qua PingBypass và không bị rubberband.
2. Không port desync-check (`C2SActualPos`/`isValid()`/dumb-pipe fallback) — bỏ qua theo hướng đơn giản hóa, luôn dùng đường proxy-tự-sinh. Nếu sau này thấy desync (client tường/vật cản mà proxy không thấy do lag), đây là chỗ cần thêm.
3. Cờ `eu.client.pingbypass.PingBypassFlags.proxyForwardingActive`/`isServer()` không còn được dùng trong `RotationManager`/`ClientPlayerEntityMixin` nữa — kiểm tra còn chỗ nào khác dùng 2 cờ này với giả định "movement bị cancel trên proxy" mà giờ SAI (VD: `ElytraFlyModule.onSendMovement` giờ cũng chạy trên proxy vì `SendMovementEvent` không còn bị chặn sớm — module này KHÔNG nằm trong phạm vi 5 module được yêu cầu, chưa kiểm tra kỹ, có thể gây side-effect nếu user bật Elytra trong lúc PingBypass).
4b. **Verify khớp earthhack cho action khác (đã re-check source thật `PbNetHandler`/`IPbNetHandler`/`ClientDiggingService`/`PbWindowClickService` trong `/tmp/3arthh4ck-ref`):**
   - `IPbNetHandler` có `default void handle(Packet<?>) {}` (no-op) — hầu hết `process*` override chỉ gọi lại default no-op. Điều này KHÔNG có nghĩa proxy earthhack "bỏ" các packet đó — nghĩa là earthhack có 1 forwarder generic khác (byte-level/`Pb2SManager`) tự relay MỌI packet trừ những type bị blacklist cần xử lý đặc biệt (`CPacketPlayer` movement, `CPacketClickWindow`). eu-client không có forwarder generic kiểu đó — thay vào đó `PbPlayHandler` gọi `forward(p)` tường minh theo từng loại packet. Về HÀNH VI, 2 cách này TƯƠNG ĐƯƠNG (cùng contract: mặc định pass-through, trừ 2 loại được can thiệp) — không phải thiếu sót.
   - **Đập/đặt block (dig/place)**: `ClientDiggingService` (client-side) chỉ CANCEL packet đào khi SpeedMine đang chạy (để SpeedMine tự gửi packet riêng) — không có cơ chế regenerate-trên-proxy nào khác cho dig/place. → eu-client hiện tại (`forward(p)` thẳng cho `handlePlayerAction`/`handleUseItemOn`, trừ khi SpeedMine chiếm quyền) **ĐÃ KHỚP**, không cần sửa.
   - **Ném đồ (Q / drop item)**: dùng chung `ServerboundPlayerActionPacket`/`CPacketPlayerDigging`, không có service riêng nào chặn — **ĐÃ KHỚP** (dumb-pipe qua `forward(p)`).
   - **Mở container (chest/shulker)**: earthhack không có xử lý đặc biệt để MỞ — S2C packet mở màn hình được forward dumb-pipe bình thường. `InventoryService` (client-side) chỉ báo cho proxy biết khi mở **inventory CỦA MÌNH** (màn hình local, không qua server) để proxy biết mà tạm dừng auto-switch slot — CHƯA PORT ở eu-client, priority thấp (chỉ ảnh hưởng module tự-switch-slot khi user đang mở túi đồ, VD AutoTrap/SpeedMine's autoSwitch có thể vô tình đổi tay trong lúc user đang thao tác túi — cosmetic/edge-case, chưa liệt vào 5 module được yêu cầu).
   - **Click trong container (crafting/chuyển đồ)**: **PHÁT HIỆN GAP THẬT + ĐÃ SỬA.** `PbNetHandler.processClickWindow` (earthhack) KHÔNG dumb-pipe — replay click lên container CỦA PROXY, lấy transaction id MỚI từ chính container proxy, build packet MỚI rồi mới gửi thật (giống hệt pattern `MotionUpdateHelper` cho movement). eu-client trước đó replay lên container proxy (đúng) NHƯNG vẫn `forward(p)` packet gốc của client (mang `stateId`/`changedSlots` từ container CLIENT, lệch pha với container PROXY mà server thật đang track) → **đã sửa** `PbPlayHandler.handleContainerClick`: build `ServerboundContainerClickPacket` mới dùng `handler.getStateId()` của container proxy (sau khi replay), giữ nguyên slot/button/containerInput, `changedSlots` rỗng + `carriedItem = HashedStack.EMPTY` (bỏ qua phần dự đoán nhanh của protocol hiện đại — worst case server chỉ full-resync chứ không reject click, chấp nhận được). Build+test pass.
5. (Chưa làm) `PbTeleport`-equivalent — xử lý lag-back cho server anti-cheat khó (nghi ngờ liên quan 6b6t hôm qua không vào được) — CHƯA research kỹ, chỉ mới đọc sourcecode `PbTeleport.java`/`ListenerPosLook.java`.
5. Còn ~5 file listener earthhack chưa đọc kỹ hết chi tiết (đã đọc lướt tên/mục đích tất cả 25 file, đọc kỹ nội dung ~20/25).

## Ghi chú quan trọng khác

- `Pb2bQueueListener`: chỉ parse text tab-header để hiển thị vị trí hàng đợi 2b2t (HUD), KHÔNG phải cơ chế đặc biệt để vượt qua anti-bot — không giải thích được vụ 6b6t hôm qua.
- Mở rương/shulker "feel high-ping" — KHÔNG phải bug, là round-trip client↔proxy↔server thật không tránh được (containers cần dữ liệu thật từ server, không thể predict/tự sinh phía nào).
- "20ms" cho hành động thủ công (ăn/place/break tay) là tôi nói QUÁ TAY lúc đầu — đúng ra: server thật thấy hành động với latency thấp (do proxy gần server), nhưng CẢM GIÁC ĐIỀU KHIỂN của người chơi vẫn phụ thuộc ping thật từ họ tới VPS — không đổi bởi kiến trúc này.
- Repo thật đã clone: `3arthqu4ke/3arthh4ck` (main, đầy đủ VPS/HeadlessMC), KHÔNG dùng `3arthqu4ke/pingbypass` (chuẩn cũ, standalone, đã deprecated) hay bản Fabric port dở dang tìm thấy trên máy local (`Downloads/3arthh4ck-Fabric-main`, thiếu hầu hết pingbypass thật).

## Bài học quy trình (để tránh lặp lại)

- KHÔNG tự tin dựa vào "research doc" viết từ session trước mà không verify lại nguồn thật khi bắt đầu code — research cũ (`PINGBYPASS_RESEARCH.md`) hoá ra có thể đã lấy nhầm/lẫn lộn nguồn.
- Khi user nói "check earthhack làm sao" — PHẢI đọc source thật (`gh repo clone`), không suy luận từ memory/research doc cũ.
- Một file/class đọc riêng lẻ dễ hiểu SAI ý nghĩa — cần trace tới CALL SITE thật (ai gọi, gọi trong context nào) mới kết luận chắc được. Bài học từ việc hiểu sai `CPacketInputService`/`Pb2SManager`/`MotionUpdateHelper` nhiều lần trong chính session này.
