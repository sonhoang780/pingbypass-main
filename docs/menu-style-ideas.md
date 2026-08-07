# MenuModule (Main Menu) — style rework, TODO

Ngày ghi: 2026-08-06. Việc còn dang dở — làm tiếp bằng Claude Design (claude.com/product/design).

## Bối cảnh

`MenuModule` (eu.client.modules.impl.core.MenuModule) thay title-screen vanilla bằng
`eu.client.gui.special.MainMenuScreen`. Hiện tại CHỈ có 1 style (dark gradient tĩnh, list
button dọc giữa màn hình, không có ModeSetting chọn style).

## Luồng làm việc đã thống nhất

1. Bạn vào claude.com/product/design, chọn project type **"UI mockups"**, viết prompt mô tả
   style muốn (dark theme, accent màu theo ClickGUI, layout list dọc/sidebar/panorama...).
2. Xem preview trực quan bên đó, chốt design ưng ý.
3. Đem screenshot/HTML export về đây → tôi dịch lại thành Java thật:
   - Thêm `ModeSetting style` vào `MenuModule.java`.
   - Style mới code thành class riêng theo pattern `MainMenuScreen.java` hiện có
     (`Renderer2D`, `EUClient.FONT_MANAGER`, `Entry` inner class cho button hover-anim).
   - `MainMenuScreen` switch render theo `style.getValue()`.
   - KHÔNG copy trực tiếp CSS/JS từ Claude Design — chỉ dùng làm tham chiếu hình ảnh, code
     Java phải viết tay lại theo API render sẵn có của project.

## Hướng đã gợi ý (chưa chọn, còn mở)

- Panorama/background động: khôi phục panorama xoay vanilla + overlay tối + accent giống ClickGui.
- Sidebar/tab ngang: button dịch trái hoặc thành thanh ngang, kiểu Sigma/Aristois.
- Particle/glow nền: giữ layout hiện tại, thêm hạt/glow trôi + logo animate.

## Trạng thái

Chưa có prompt/mockup nào được tạo bên Claude Design tại thời điểm ghi chú này. Việc tiếp theo:
bạn viết prompt bên đó → quay lại đưa kết quả cho tôi code.
