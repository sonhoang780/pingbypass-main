# Fix: Rotate=Normal + MovementFix flag khi via 1.21.x (không flag ở via 1.20.x) — 2026-08-15

## Bối cảnh
Sau khi revert Rotate Normal/Packet của AutoCrystal/SpeedMine về đúng bản gốc 1.21.4 (xem
`SESSION_2026-08-14_ROTATE_NORMAL_REVERT.md`), user báo: Rotate=Normal + MovementFix chạy đúng
trên ViaFabricPlus downgrade **1.20.x**, nhưng **flag khi di chuyển + AutoCrystal** trên
ViaFabricPlus downgrade **1.21.x**.

## Root cause
`AutoCrystalModule` (client-side, không phải proxy) có 3 chỗ **bắn 2 packet cho cùng 1 target**
khi Rotate=Normal đang active:

1. `legacyRotate(target, ...)` — queue rotation, tới tick sau sẽ đi ra qua packet `sendPosition()`
   bình thường (đúng nhịp, đúng bản gốc).
2. **Ngay sau đó**, `packetRotate(target)` — bắn thêm 1 packet `ServerboundMovePlayerPacket.PosRot`
   RIÊNG, ngoài nhịp tick bình thường, cho **CÙNG MỘT target**. Đây là hành vi bản gốc 1.21.4 gốc
   cũng có (đã verify diff trực tiếp, không phải lỗi port) — nhưng chính packet thừa này là vấn đề.

**Vì sao chỉ lộ trên 1.21.x, không lộ trên 1.20.x:**
- Từ 1.21.2 trở lên, client gửi `ServerboundClientTickEndPacket` đánh dấu ranh giới mỗi tick server-
  tick — server biết chính xác bao nhiêu packet "hợp lệ" nên có trong 1 tick. Packet thừa (không do
  tick sinh ra tự nhiên) → lộ ra là bất thường → GrimAC/anti-cheat có prediction cycle nhận diện
  được extra position-update → flag movement.
- ViaFabricPlus khi downgrade xuống 1.20.x **strip** hẳn `ServerboundClientTickEndPacket` (giao thức
  1.20.x không có khái niệm này) → server không còn cách nào biết "packet này có nằm ngoài nhịp tick
  hay không" → packet thừa hoà lẫn vào, không phân biệt được với aim bình thường → không flag.

(Cơ chế này đã được ghi chú sẵn trong `RotationUtils.getRotations`'s comment về nhiễu ±2°, chỉ chưa
từng áp dụng cho case `packetRotate` duplicate cụ thể này.)

## Fix
**Lệch có chủ đích khỏi bản gốc 1.21.4** (theo yêu cầu trực tiếp của user, không phải do porting
sai) — 3 vị trí trong `AutoCrystalModule.java`, đổi guard packetRotate từ
`!rotate.getValue().equalsIgnoreCase("None")` (bắn cho cả Normal lẫn Packet) sang
`rotate.getValue().equalsIgnoreCase("Packet")` (chỉ bắn khi Rotate=Packet):

- `placeCrystals()` — vị trí đặt chính (dòng ~519-532)
- `mineIgnorePlace()` — vị trí đặt instant-break-detonate #1 (dòng ~1097-1100)
- `mineIgnoreDetonate()` — vị trí đặt instant-break-detonate #2 (dòng ~1131-1136)

Rotate=Normal giờ **chỉ còn 1 packet/tick** cho target đặt (qua `legacyRotate` + `sendPosition()`
tự nhiên), không còn packet phụ ngoài nhịp. Rotate=Packet **không đổi gì** — vẫn là cơ chế duy nhất
của nó, không có `legacyRotate` đi kèm nên vẫn cần `packetRotate` để hoạt động.

**Không đổi:**
- `packetRotate(entity)` trong vòng lặp tấn công crystal obstruction (target KHÁC — chính entity
  crystal, không phải vị trí đặt) — vẫn bắn cho cả Normal/Packet, vì đây là aim tức thời cần cho
  packet tấn công (`PlayerInteractEntityC2SPacket.attack`) vượt qua check reach/facing phía server,
  không phải bản sao trùng lặp.
- `SpeedMineModule` — đã kiểm tra kỹ, **cả 2 site `packetRotate` đã sẵn gate `Packet`-only từ
  trước**, không có bug tương tự. Không cần sửa gì.
- `ServerAutoCrystal` (proxy-side) — Normal ở đây vốn là **no-op cố ý** (theo đúng thiết kế bản gốc,
  proxy không đụng camera), `packetRotate` là cơ chế **duy nhất** cho mọi mode ≠ None ở đây — không
  có `legacyRotate` đi kèm nên không có gì để trùng lặp, giữ nguyên.

## Build
`./gradlew.bat compileJava` — compile sạch.

## Việc khác đã làm trong phiên (tóm tắt, chi tiết xem file trước)
- `LivingEntityRendererMixin`: sửa lại gọi `getRenderRotations()` 3 lần riêng biệt (đúng behavior
  thật của bản gốc, không phải 1 lần như code "sạch" trước đó) — cosmetic third-person model only.
- `RotationManager`: Sprint Grim (`ClientRotationEvent`) được gán priority 4 trong bảng
  `LEGACY_PRIORITIES` (giữa SpeedMine=3 và SelfFill=5) — Sprint Grim giờ thắng arbitration so với
  AutoCrystal/SpeedMine Rotate=Normal, theo yêu cầu.
- `WorldUtils.isPlaceable`/`isCrystalPlaceable`: loại trừ `AbstractArrow` (Arrow/SpectralArrow) khỏi
  danh sách entity chặn đặt block — arrow cắm không chặn đặt trong vanilla, giờ client cũng vậy.
- `WorldUtils.isInstantBreakable`/`instantBreak` (mới) + `SurroundModule`: tự động phá block
  hardness-0 (hoa, nấm, cỏ, cây con...) đang chiếm vị trí surround thay vì bỏ qua vĩnh viễn.
- `Frame.java` (ClickGui): sửa lỗi "hiệu ứng lò xo" — layout/scroll-bound giờ chốt ngay chiều cao
  cuối cùng khi 1 module mở ra thay vì đuổi theo animation, giúp module cuối danh sách (vd Surround)
  luôn cuộn tới được ngay lập tức.

## Chưa test in-game
Toàn bộ thay đổi trong phiên này (bao gồm cả phiên trước) mới chỉ compile-verify, **chưa build jar
+ test thật trong game**. Cần build, copy vào `example-addon-master/run/mods/`, chạy
`gradlew.bat runBoze`, test lại đúng kịch bản: Rotate=Normal + MovementFix, qua ViaFabricPlus
1.21.x, xem còn flag di chuyển/AutoCrystal không; đồng thời test lại 1.20.x để đảm bảo không đổi
hành vi (vốn đã đúng).
