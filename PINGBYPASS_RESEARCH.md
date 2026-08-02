# PingBypass Research Notes

So sánh kiến trúc PingBypass của repo này (26.1.2, port từ 1.21.4) với 2 nguồn tham khảo:
bản gốc `godmoduleu/pingbypass` (1.21.4) và `3arthqu4ke/3arthh4ck` (1.12.2, Forge — theo
đánh giá của user, chạy "cực mượt, không hề gặp lỗi nào").

## 1. So sánh với bản gốc `godmoduleu/pingbypass` (1.21.4)

Cache source gốc: `/tmp/orig_pb/*.java` (37 file, chỉ phần core pingbypass — không có
SpeedMine/AutoCrystal, 2 module đó là add-on riêng của EUClient).

### Phát hiện quan trọng nhất: `PbSession.java`

`server/PbSession.java` (`extends Connection`) override `setupOutboundProtocol`/
`setupInboundProtocol` để **thao túng Netty pipeline trực tiếp** (swap thẳng handler
`"encoder"`/`"decoder"`) thay vì dùng cơ chế `UnconfiguredPipelineHandler`/
`OutboundConfigurationTask` mặc định của vanilla. Comment gốc: *"avoiding conflicts with
Fabric API's packet splitter/merger handlers"*.

Đây **chính là kỹ thuật né đúng bug crash `OutboundConfigurationTask`** mà session hôm nay
đào ra (crash khi server push reconfigure giữa phiên trên 6b6t/2b2tpractice, do xung đột
`fabric-networking-api-v1`) — tác giả gốc đã biết và né từ 1.21.4 rồi, nhưng **chỉ áp dụng
cho connection client thật → proxy** (`ProxyServer` dùng `new PbSession()` khi accept
player). Connection **proxy → server thật** (`mc.getConnection()`, do vanilla tự tạo qua
`ConnectScreen.startConnecting()`) chưa bao giờ được bọc — cả ở bản gốc lẫn bản port. Không
phải port bỏ sót, là kiến trúc gốc chưa từng vá hết.

**Fix đã áp dụng**: `ConnectionSafeReconfigureMixin.java` — áp kỹ thuật của `PbSession` cho
MỌI `Connection` qua mixin (không chỉ connection do code mình tạo).

### Các phát hiện khác

- **`S2CForwarder` multi-tenant bug — CÓ TỪ BẢN GỐC**, không phải do port. Bản gốc cũng dùng
  `List<ClientConnection> connections` + 1 `serverConnection` chung (multi-player qua 1 proxy
  là chủ đích từ đầu), filter `event.getConnection() == clientConnection` y hệt — cùng lỗ hổng
  "không phải client tôi thì coi là server thật" đã vá trong session này.
- `ClientboundTransferPacket`/`StartConfiguration` suppression **không tồn tại** trong bản gốc
  luôn — không hề có logic đặc biệt cho reconfigure giữa phiên.
- `WorldStateReplay.createSpawnInfo()`: bản gốc có `isFlat()` heuristic, bản port hardcode
  `false` (chỉ ảnh hưởng cosmetic, không gameplay).
- `WorldStateSender.java`: bản gốc gần như toàn TODO stub, bản port thay bằng gọi thật
  `WorldStateReplay.replay()` — cải thiện, không phải bug.
- Phần còn lại (`PingBypassConfig`, `PbConfigurationHandler`, `PbLoginHandler`,
  `PbStatusHandler`, `PbHandshakeHandler`, `PbPasswordHandler`, `PbWaitingHandler`,
  `ClientInputForwarder`, `ProxyModuleManager`, toàn bộ `protocol/packets/*`,
  `LobbyWorldSender`, `ProxyServer`, `ProxyServerTickListener`, `RegistryCache`): rename
  Yarn→Mojmap thuần túy, không lệch logic.

### README bản gốc — phạm vi test thực tế

- Tested chính thức: `crystalpvp.cc` / `grim.crystalpvp.cc` (CrystalPvP/Grim anticheat).
- **"Not tested on: `2b2t` và các server anarchy lớn tương tự"** — ghi rõ trong README.
  Toàn bộ lớp bug gặp trên 2b2tpractice/6b6t (transfer packet, StartConfiguration crash,
  anti-bot probe...) nằm ngoài phạm vi test/support của tác giả gốc — không phải port thiếu,
  server class hoàn toàn khác (anarchy/multi-backend/anti-proxy nặng) so với target gốc
  (CrystalPvP — 1 server, không multi-backend).
- Có license key (gọi API xác thực từ xa trong `script.sh`), không free hoàn toàn dù code MIT.
- Không tìm thấy Discord/kênh hỗ trợ công khai nào trong repo/profile tác giả.

## 2. So sánh kiến trúc với `3arthqu4ke/3arthh4ck` (1.12.2, Forge)

Client rất trưởng thành, PingBypass "cực mượt" theo user. 1.12.2/Forge/Yarn-mapping khác hẳn
API so với 26.1.2/Fabric/Mojmap — không copy code trực tiếp được, chỉ lấy **pattern kiến
trúc**.

### Khác biệt gốc rễ nhất: KHÔNG forward movement packet đã simulate — forward RAW INPUT

`PbMoveListener` (chạy ở **CLIENT**, không phải proxy):

```java
public void invoke(MoveEvent event) {
    if (PingBypass.isConnected()) event.setCancelled(true);
}
```

Khi PingBypass đang connect, client **hủy hẳn việc tự build/gửi movement packet của chính
nó**. Thay vào đó `ClientInputService` forward **raw mouse/keyboard input**
(`C2SMousePacket`/`C2SKeyboardPacket`/`C2SPostKeyPacket`) lên proxy mỗi input-event.
`ServerInputService` phía proxy nhận raw input đó, feed vào `Mouse`/simulation của chính
proxy — toàn bộ physics/movement/rotation simulation chỉ chạy **đúng 1 lần, trên proxy**, y
hệt 1 client thật đang chơi trực tiếp từ đó. Client vật lý chỉ còn là "bàn phím + chuột từ
xa", không tự sinh packet movement/rotation nào cả.

**Đây là lý do thật họ không bao giờ có "2 nguồn rotation xung đột"**: không phải khéo tránh
xung đột — **chỉ có đúng 1 nguồn tồn tại**. Kiến trúc dumb-pipe của repo này (forward packet
client đã tự simulate xong + patch chèn/ghi đè thêm ở giữa) luôn có nguy cơ 2-nguồn về bản
chất, dù đã piggyback (fix rotation hôm nay chỉ giảm, không triệt tiêu hẳn).

**Đổi sang forward-raw-input là refactor lớn**, không phải fix nhỏ — cần đánh giá kỹ trước
khi làm.

### Tip nhỏ áp dụng ngay được: epsilon-jitter cho rotation dedupe

`ListenerRotations` (submodule `sautocrystal`) hook vào `MotionUpdateEvent` (tương đương
`UpdateMovementEvent` của ta), ghi trực tiếp `event.setYaw()/setPitch()` — đúng pattern
piggyback vừa tự làm hôm nay. Nhưng có thêm: nếu yaw/pitch mới **giống hệt** lần gửi trước
(`yawDif==0 && pitchDif==0`), họ cộng thêm offset cực nhỏ `0.0004f` (đảo dấu mỗi lần) để ép
vanilla luôn coi "có thay đổi" và gửi rotation packet — vì vanilla mặc định **bỏ qua** gửi
rotation nếu giá trị y hệt lần trước.

→ **Chưa áp dụng cho repo này**. Nếu target không đổi vài tick liên tiếp, fix override
yaw/pitch hiện tại (`PbPlayHandler.handleMovePlayer`) có thể vô tình bị vanilla dedupe im
lặng bỏ qua việc gửi packet rotation mới.

### Module architecture: 2 class riêng biệt, không dùng `if(proxy)` chung 1 class

`PbModule`/`SyncModule` + submodule `sautocrystal`/`sautototem`/`sinventory`/`sSafety`: họ có
**2 class Module hoàn toàn tách biệt** — client-side gốc (`AutoCrystal.java`) và proxy-side
riêng (`ServerAutoCrystal.java`), nối qua `PbModule` (wrap 1 Module thường thành phiên bản
hiển thị GUI phía PingBypass, copy Setting) + `SyncModule` (đồng bộ setting theo yêu cầu thủ
công, không tự động push mỗi lần đổi).

So với repo này: **1 class duy nhất** (`AutoCrystalModule`, `SpeedMineModule`...) với
`if (isRunningOnProxy())`/`if (shouldRunOnProxy())` rải khắp nơi. Đây là nguồn gốc phần lớn
race-condition đã tìm & fix trong session hôm nay: SpeedMine giữ pickaxe xuyên nhiều tick,
AutoCrystal cướp slot giữa event đồng bộ, `handleSetCarriedItem` nuốt nhầm packet client
thật... — vì state (`primary`/`secondary`/`startSlot`, `mc.player` mirror) bị chia sẻ/tranh
chấp giữa 2 "chế độ" chạy chung trong cùng object.

Tách hẳn class riêng cho proxy (như họ làm) sẽ triệt tiêu tận gốc lớp bug này — **refactor
rất lớn**, không phải fix nhỏ.

### Phát hiện thêm: hạ tầng raw-input ĐÃ CÓ SẴN trong repo này — nhưng là code chết

`src/main/java/eu/client/pingbypass/input/ClientInputForwarder.java` +
`src/main/java/eu/client/pingbypass/protocol/packets/C2SInputPacket.java` **đã tồn tại từ
trước** trong repo, docstring ghi rõ: *"The proxy replays these inputs through its own game
loop, generating properly sequenced packets to the real server"* — đúng y chang kiến trúc
`PbMoveListener`/`ClientInputService`/`ServerInputService` của 3arthh4ck.

Nhưng:
- `ClientInputForwarder` chỉ forward **2 nút chuột** (attack=0, use=1), KHÔNG forward
  keyboard (WASD/jump/sneak/sprint) dù `C2SInputPacket` đã định nghĩa sẵn `TYPE_KEY`.
- Không có `mc.getConnection().send()` nào của client bị **hủy** — client vẫn tự simulate và
  gửi movement/attack packet song song với việc gửi thêm `C2SInputPacket` — tức là ĐANG CÓ
  CẢ 2 NGUỒN cùng lúc (input thật + input forward), không phải thay thế.
- Quan trọng nhất: **grep toàn bộ `handler/*.java` không thấy chỗ nào xử lý
  `C2SInputPacket` trên proxy** — packet gửi lên proxy rồi bị bỏ xó (không match case nào
  trong `PbPlayHandler.handlePbPayload`). Có nghĩa hạ tầng làm dở dang, chưa từng hoạt động
  thật.

→ Ai đó (port trước đây, hoặc chính tác giả gốc EUClient) đã có Ý ĐỊNH làm đúng kiến trúc
raw-input như 3arthh4ck, nhưng bỏ dở giữa chừng.

## 5. Kế hoạch — TỪ BỎ kiến trúc `proxyEnhanced` hiện tại, viết lại theo tổ chức earthhack

Quyết định: không vá thêm lên kiến trúc `proxyEnhanced`/`shouldRunOnProxy()`/
`isRunningOnProxy()` rải rác hiện tại của EUClient nữa (nguồn gốc phần lớn bug đã tìm suốt
session hôm nay). Viết lại **toàn bộ** cách 18 module dưới đây tích hợp pingbypass, theo đúng
tổ chức package của earthhack (mục 4) — không giữ lại bất kỳ phần nào của cách làm cũ, không
tham khảo lại code `proxyEnhanced` cũ khi thiết kế cái mới.

18 module đang có `proxyEnhanced = true` cần migrate:
`AutoArmorModule`, `AutoCrystalModule`, `AutoTotemModule`, `AutoTrapModule`, `AutoWebModule`,
`BlockerModule`, `CombatPlace`, `CriticalsModule`, `HoleBuilder`, `HoleFillModule`,
`KillAuraModule`, `OpponentScaffold`, `SelfFillModule`, `SelfTrapModule`, `SurroundModule`,
`FakePlayerModule`, `SpeedMineModule`, `ThrowXPModule`.

### Cây package mới (thay thế hoàn toàn cách rải trong `modules/impl/*`)

Mirror đúng cấu trúc earthhack, map sang Mojmap 26.1.2/Fabric:

```
eu.client.pingbypass/
├── handler/          (giữ nguyên — state machine kết nối, đã tổ chức gần giống nethandler/)
├── protocol/          (giữ nguyên khung PbPacket/packets)
├── server/            (giữ nguyên — ProxyServer, S2CForwarder, WorldStateReplay...)
├── input/              ← MỚI, thay cho ClientInputForwarder cũ (xóa bản cũ, viết lại)
│   ├── ClientInputService.java   (client: bắt KeyboardHandlerAccessor/MouseAccessor, gửi raw input)
│   └── ServerInputService.java   (proxy: nhận raw input, feed vào ClientInput/rotation của mc.player)
├── event/              ← MỚI, tách khỏi EUClient.EVENT_HANDLER dùng chung
│   └── (event riêng cho pingbypass, theo mẫu Disconnect/Packet/S2CCustomPacket của earthhack)
└── modules/            ← MỚI, thay thế HOÀN TOÀN proxyEnhanced/isRunningOnProxy()
    ├── PbModule.java          (base class: wrap 1 Module client-side thành bản có mặt trên proxy)
    ├── SyncModule.java        (đồng bộ setting module gốc ↔ bản proxy)
    ├── PbModuleManager.java   (registry các module có bản proxy)
    └── submodules/
        ├── crystal/    ServerAutoCrystal.java, ListenerRotations.java, ListenerTick.java, ListenerRender.java
        ├── totem/      ServerAutoTotem.java, ListenerSetSlot.java, ListenerTick.java
        ├── inventory/  ServerInventory.java (slot-switch tập trung, dùng chung cho mọi submodule cần đổi tay)
        └── safety/     ServerSafety.java
```

### Phân loại 18 module — module nào cần submodule riêng, module nào chỉ cần raw-input

Theo đúng tiêu chí mục 4 (earthhack): module cần **theo dõi player khác real-time sát tick
server** → submodule proxy riêng. Module chỉ cần **input thuần** (đào/đặt/tấn công theo góc
nhìn, không cần optimize riêng quanh tick server) → raw-input-forwarding lo hết, không cần
code riêng.

**Cần submodule riêng** (tương tự `sautocrystal`/`sautototem`):
`AutoCrystalModule` → `submodules/crystal/`, `AutoTotemModule` → `submodules/totem/`,
`KillAuraModule`, `CombatPlace`, `SurroundModule`, `BlockerModule`, `AutoTrapModule`,
`AutoWebModule`, `SelfTrapModule`, `HoleBuilder`, `HoleFillModule`, `OpponentScaffold` —
nhóm combat/target-tracking, cần biết vị trí player khác chính xác theo tick.

**Chỉ cần raw-input, KHÔNG viết submodule riêng** (dẹp hẳn code proxy hiện có của các module
này, xóa toàn bộ `isRunningOnProxy()`/`serverSend()`/`isProxyActive()` trong đó):
`SpeedMineModule`, `AutoArmorModule`, `CriticalsModule`, `SelfFillModule`, `ThrowXPModule` —
đào/đổi giáp/critical-hit/self-fill/ném xp đều chỉ là hành động theo input+góc nhìn, input
redirect tự lo, y hệt kết luận mục 4 về Speedmine/AutoMine của earthhack.

**Không phải combat, xử lý riêng**: `FakePlayerModule` — không tương ứng module nào bên
earthhack (không có tính năng fake-player). Giữ nguyên cơ chế broadcast hiện tại (không liên
quan input/movement, không cần raw-input hay submodule).

### Việc cần làm, theo thứ tự

1. **Xóa** `ClientInputForwarder.java`/`C2SInputPacket.java` hiện tại (code chết, chỉ forward
   2 nút chuột, không proxy-side consumer) — viết lại từ đầu thành `input/ClientInputService`
   + `input/ServerInputService` đầy đủ (mouse look delta + toàn bộ keybind di chuyển +
   attack/use), không kế thừa gì từ bản cũ.
2. Cancel hẳn việc client tự gửi movement (`ClientPlayerEntityMixin.sendMovementPackets`, bỏ
   nhánh "On CLIENT: do NOT cancel") và tự xử lý input di chuyển cục bộ khi
   `proxyForwardingActive == true` — client chỉ còn là bàn phím/chuột từ xa.
3. Viết `pingbypass/modules/PbModule.java` + `SyncModule.java` + `PbModuleManager.java` —
   khung cho toàn bộ submodule sau này, độc lập với `eu.client.modules.Module`/
   `eu.client.managers.ModuleManager` hiện tại (không sửa 2 file đó, chỉ thêm khung mới song
   song).
4. Viết `submodules/crystal/ServerAutoCrystal.java` trước (module phức tạp nhất, nhiều bug
   nhất hôm nay) — port toàn bộ logic từ `AutoCrystalModule` hiện tại NHƯNG bỏ hẳn mọi
   `if(proxy)`, viết như thể đang viết 1 module client-side bình thường chạy trên chính
   `mc.player` của proxy.
5. Sau khi `ServerAutoCrystal` chạy ổn, dọn `AutoCrystalModule` gốc: xóa `proxyEnhanced`,
   xóa mọi nhánh `isRunningOnProxy()`/`shouldRunOnProxy()`/`serverSend()` — trả về đúng 1
   module client-only như trước khi có pingbypass.
6. Lặp lại bước 4-5 cho `totem`/`inventory`/`safety`, rồi migrate nhóm raw-input-only
   (SpeedMine, AutoArmor, Criticals, SelfFill, ThrowXP) — xóa sạch code proxy trong các file
   đó, không để lại nhánh proxy nào.
7. Xóa `PingBypassFlags.clientOnGround`/`clientHorizontalCollision` và logic override
   yaw/pitch trong `PbPlayHandler.handleMovePlayer` (patch tạm hôm nay cho kiến trúc dumb-pipe
   cũ) — không cần nữa vì proxy tự sinh packet rotation từ chính input redirect, chỉ còn 1
   nguồn.

### Điểm cần đo trước khi coi là xong
Latency thêm 1 hop cho input (client→proxy chờ xử lý→simulate, thay vì client tự simulate
ngay). Cảm giác điều khiển (feel) có thể khác — test thật trên server, không chỉ dựa lý
thuyết. Việc lớn, làm từng module một, không đổi toàn bộ 18 module cùng lúc.

### Các phát hiện khác

- `PbBlockBreakService`: forward break-block thành packet C2S riêng
  (`C2SDamageBlockPacket`/`C2SClickBlockPacket`) — không có cơ chế "giữ tool xuyên nhiều
  tick" kiểu SpeedMine cũ của ta (đã revert hôm nay).
- `sinventory/ServerInventory.java`: chỉ có 1 setting `Delay` (5-60ms), không switch/switchback
  phức tạp — gợi ý slot-switch bên proxy của họ đơn giản, tách biệt hẳn khỏi block-break logic.
- `FriendSerializer`: CÓ sync friends lên proxy thật (`C2SFriendPacket` mỗi lần add/remove +
  clear-resend khi cần) — xác nhận repo này thiếu cơ chế này trước hôm nay (đã tự fix, thêm
  `C2SFriendSyncPacket`, cùng ý tưởng).

## 3. Tóm tắt fix/revert trong session hôm nay (tham chiếu)

### Đã fix (còn giữ)
- `S2CForwarder` — match đúng `getServerConnection()` thay vì suy luận "không phải client tôi".
- `ClientConnectionMixin.doSendPacket$HEAD` — generalize filter theo package thay vì liệt kê
  từng loại packet.
- `ConnectionSafeReconfigureMixin` (mới) — áp kỹ thuật `PbSession` cho mọi `Connection`, sửa
  đúng gốc crash `OutboundConfigurationTask`.
- `TransferRehook` (mới) — rewire `serverConnection`/`S2CForwarder`/`PbPlayHandler` sau khi
  proxy's own connection bị thay (backend transfer/worker-switch).
- `PbWaitingHandler`/`WorldStateReplay` — resolve registry theo đúng instance đang bind cho
  encoder, tránh crash `dimension_type` sau reconnect.
- `AutoCrystalModule` — TTL `placedCrystals` 50ms → 500ms (đủ phủ round-trip thật).
- `PbPlayHandler.handleMovePlayer` — override yaw/pitch ngay trên packet client forward
  (piggyback) thay vì bắn thêm packet `.Rot` riêng; echo `clientOnGround`/
  `clientHorizontalCollision` thật cho các packet rotate injected còn lại.
- `SpeedMineModule` — thêm `onDisable()`, sửa `cancel()` trả `startSlot`, bỏ điều kiện
  `needSwitch` sai ngữ cảnh, **revert kiến trúc giữ pickaxe xuyên nhiều tick** về lại kiểu
  local (chỉ đổi đúng khoảnh khắc STOP_DESTROY).
- `PbPlayHandler` — mirror `startUsingItem()`/`stopUsingItem()` (trước đó `isUsingItem()`
  luôn `false` trên proxy, gây SpeedMine cắt ngang ăn táo giữa chừng).
- `FriendManager`/`PingBypassModule` — thêm sync friends list lên proxy (`C2SFriendSyncPacket`,
  mới).

### Đã revert (sai thủ phạm hoặc theo yêu cầu)
- `KillAuraModule`/`AutoCrystalModule` — filter `entity.getId() < 0` (loại FakePlayer khỏi
  target scan) — revert, không phải nguyên nhân kick 2b2tpractice.
- `AutoCrystalModule.calculatePlacements` — merge 2 lần `getEntities()` thành 1 — revert về
  bản gốc theo yêu cầu user.
- Logic hủy hẳn `ClientboundStartConfigurationPacket` trong `ClientConnectionMixin` — thay
  bằng `ConnectionSafeReconfigureMixin` (fix đúng gốc thay vì né).

### Còn nghi vấn / chưa xử lý
- Rubberband khi di chuyển + AutoCrystal: đã giảm đáng kể (piggyback fix), nhưng root cause
  sâu hơn là kiến trúc dumb-pipe 2-nguồn (xem mục 2) — chưa triệt tiêu hoàn toàn.
- Epsilon-jitter cho rotation dedupe (tip từ 3arthh4ck) — chưa áp dụng.
- "Target cả self" trong AutoCrystal — nghi ngờ liên quan desync vị trí proxy-mirror, chưa
  xác nhận bằng log.

## 4. SpeedMine/AutoMine trên proxy + tổ chức module tổng thể

### SpeedMine KHÔNG có submodule proxy riêng — và tại sao không cần

Không tồn tại `sspeedmine`/`sautomine` nào trong `submodules/` (chỉ có sSafety,
sautocrystal, sautototem, sinventory). `Speedmine.java`/`AutoMine.java` chỉ có **đúng 1 bản**,
nằm ở `impl/modules/player/speedmine/` và `impl/modules/player/automine/` — code client-side
bình thường, không nhánh `if(proxy)` nào.

Lý do khớp thẳng với phát hiện mục 2 (`PbMoveListener` hủy movement packet + forward raw
input): vì **proxy chạy chính là 1 tiến trình Minecraft đầy đủ, load cùng 1 jar mod**, khi
input được redirect sang đó thì `Speedmine`/`AutoMine` cứ chạy y hệt local — module không cần
biết/quan tâm nó đang chạy trên máy nào, vì tại một thời điểm chỉ CÓ MỘT bên đang thực sự
simulate game loop (client tự chơi bình thường, hoặc proxy nhận input redirect). Không như
port hiện tại — nơi `SpeedMineModule`/`AutoCrystalModule` phải tự biết mình đang chạy ở đâu
(`isRunningOnProxy()`) vì CẢ 2 bên (client thật + proxy) đều load module và có thể cùng chạy
tick song song trên 2 tiến trình riêng biệt.

3 file liên quan mining dưới package `pingbypass` xác nhận thêm:

- `ClientDiggingService` (client-side): khi PingBypass đang connect VÀ Speedmine bật (Mode ≠
  Reset), **hủy hẳn** `CPacketPlayerDigging` (START/ABORT/STOP_DESTROY_BLOCK) của chính client
  nếu là digging "bình thường" (`isNormalDigging()`) — y hệt tinh thần `PbMoveListener` hủy
  movement packet: client không tự gửi packet đào, để phía simulate-thật-sự (proxy) tự quyết.
- `PbBlockBreakService`: lắng nghe `DamageBlockEvent`/`ClickBlockEvent` (event tầng game-logic,
  không phải packet) và forward thành `C2SDamageBlockPacket`/`C2SClickBlockPacket` riêng của
  PingBypass. Vì input được redirect, những event này được sinh ra bởi **bất kỳ bên nào đang
  thực sự chạy simulation** tại thời điểm đó — cùng 1 listener xử lý được cả 2 trường hợp,
  không cần phân biệt local/proxy.
- `PbPlayerDiggingListener`: chặn `RELEASE_USE_ITEM` gửi trùng khi "unauthorized" — chi tiết
  vặt về dedupe packet, không phải logic ăn-uống như `SpeedMineModule.interactPaused` của ta.

→ Vậy AutoCrystal/AutoTotem/Inventory mới cần submodule proxy riêng (`ServerAutoCrystal`...)
không phải vì kiến trúc raw-input thất bại với chúng, mà vì các module đó cần thêm khả năng
**chỉ có ý nghĩa khi chạy trên proxy** (theo dõi vị trí toàn bộ player khác theo thời gian
thực sát server nhất có thể, tối ưu quanh tick server thay vì tick input) — mining/movement
thuần túy thì raw-input-forwarding tự nhiên đã đủ, không cần viết lại.

### Bản đồ tổ chức package đầy đủ

**`me.earth.earthhack.pingbypass`** (tầng framework, tương đương `eu.client.pingbypass` của
ta):

| Package | Vai trò | So với ta |
|---|---|---|
| `PingBypass.java` (root) | Entry point/state singleton của cả tính năng | ~ `EUClient.PROXY_SERVER`/`PINGBYPASS_CONFIG` |
| `event/` | 3 event class riêng cho pingbypass (Disconnect/Packet/S2CCustomPacket) | ta dùng chung `EUClient.EVENT_HANDLER`, không tách event pingbypass riêng |
| `input/` | `ClientInputService`/`ServerInputService` + `Mouse`/`Keyboard` — forward raw input | **ta không có gì tương đương** — đây là khoảng trống kiến trúc lớn nhất |
| `listeners/` | ~25 file, mỗi file 1 concern hẹp (move, digging, speed, inventory, window-click, resource-pack, statistics, anti-troll...) | ta gộp phần lớn vào `PbPlayHandler` (1 file to, switch-case theo packet type) |
| `modules/` | `PbModule`/`SyncModule`/`PbModuleManager`/`PbAC` — base class cho "module có bản proxy" | ta không có base class này — mỗi module tự thêm `proxyEnhanced`/`isRunningOnProxy()` |
| `nethandler/` | Handshake/Login/Password/Status/WaitingForJoin — state machine kết nối | ~ `eu.client.pingbypass.handler.*`, tổ chức gần giống nhau nhất |
| `netty/` | `PbNetworkManager`/`PbNetworkSystem`/`PbLegacyPingHandler` — tự quản lý network pipeline riêng | ta không có — dùng thẳng `Connection` vanilla (+ `PbSession` override 1 phần) |
| `protocol/` | `PbPacket` base + `c2s/`/`s2c/` (~35 packet class, nhiều hơn hẳn ta) | ~ `eu.client.pingbypass.protocol.packets`, họ có nhiều packet chuyên biệt hơn (StepPacket, SpeedPacket, RiddenEntityPosition, ConfirmLagBack...) — ta gộp chung qua `S2CSlotSyncPacket`/settings-generic |
| `proxy/` | **6 sender class tách riêng theo concern**: `ChunkSender`, `EntitySender`, `PlayerListSender`, `ScoreboardSender`, `TimeAndWeatherSender`, `WorldSender` (điều phối chung) | ta gộp hết vào 1 file `WorldStateReplay.java` — họ tách nhỏ hơn |
| `util/` | Helper linh tinh (`MotionUpdateHelper`, `PacketBufferUtil`) | tương đương |

**`me.earth.earthhack.impl.modules.client.pingbypass`** (bản thân module "PingBypass" hiện
trên GUI + toàn bộ submodule proxy-side):

- Top-level `Listener*.java` (CPacket/CustomPayload/EnablePingBypass/Init/KeepAlive/Login/
  NoUpdate/Tick): glue lifecycle của riêng module PingBypass (bật/tắt, tick, keepalive) — tách
  biệt khỏi state machine kết nối (nằm ở `nethandler/` phía trên).
- `guis/`: UI riêng cho pingbypass (nhập IP/pass, màn hình connecting) — ta dùng chat-command +
  ClickGui chung, không có GUI riêng.
- `packets/`: `PayloadIDs`/`PayloadManager`/`PayloadReader` — một tầng đóng gói payload
  **khác** với `protocol/` ở trên (có vẻ là lớp routing custom-payload-channel, tương đương
  `PbCustomPayload`/`PbProtocolHandler` của ta).
- `serializer/`: `friend/` + `setting/`, mỗi cái có `ListenerTick`/`ListenerDisconnect` riêng
  để tự động re-sync định kỳ và dọn dẹp khi disconnect — ta chỉ sync friends lúc add/remove +
  connect (vừa thêm hôm nay), KHÔNG có tick-based re-sync định kỳ hay dọn dẹp lúc disconnect.
- `submodules/`: đã phân tích ở mục 2 và trên — sSafety/sautocrystal/sautototem/sinventory.

**Kết luận tổ chức**: họ giữ toàn bộ code pingbypass dưới 2 package tree tách biệt hoàn toàn
khỏi `impl/modules/combat|player|...` bình thường (**không** rải `if(proxy)` vào module gốc,
trừ đúng những module thật sự cần bản proxy riêng thì có submodule tương ứng). Ta thì NGƯỢC
LẠI: package `eu.client.pingbypass` chỉ chứa phần khung (handler/protocol/server), còn
proxy-awareness bị rải trực tiếp vào từng module gốc (`AutoCrystalModule`, `SpeedMineModule`,
`FakePlayerModule`...) qua `proxyEnhanced`/`shouldRunOnProxy()`/`isRunningOnProxy()`. Đây là
khác biệt tổ chức lớn nhất, và như mục 2 đã nêu, refactor theo hướng của họ là việc lớn — join
điểm khả thi nhất để bắt đầu (nếu muốn) là làm raw-input-forwarding cho các module
KHÔNG cần submodule riêng (mining/movement), giữ nguyên các module cần submodule
(AutoCrystal/AutoTotem) như hiện tại.
