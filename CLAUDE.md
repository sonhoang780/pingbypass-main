# Caveman mode — mandatory

All output for this repo — chat replies, code comments, Javadoc, commit messages, explanations — must use caveman full mode (terse, drop articles/filler/pleasantries/hedging, fragments OK, technical substance intact). Applies everywhere unless the user explicitly asks for detailed/normal prose in that specific instance.

## MCP & Context Optimization

**Discovery (đọc hiểu code) — ưu tiên CodeGraph.** `codegraph_explore` TRƯỚC Read/Glob/Grep cho mọi câu hỏi discovery ("X hoạt động sao", tìm bug, trace usage, hiểu cấu trúc, blast-radius trước khi sửa) — kể cả khi đã biết file nằm đâu. 1 call trả verbatim source (line-numbered, coi như đã Read) + caller list + call path kể cả dynamic-dispatch grep không trace được. Không cần `search` riêng — tên symbol hoặc câu hỏi tự nhiên đều được. Chỉ fallback Read/Glob/Grep khi codegraph không đủ (binary, config/JSON nhỏ, hoặc ngoài `.codegraph/` index).

**Bẫy thường gặp:** biết tên file → tự động Glob+Read cả file. Sai — biết file không có nghĩa biết nội dung cần tìm, `codegraph_explore` vẫn rẻ hơn.

**Editing (sửa code) — Serena bắt buộc cho file to, tự do cho file nhỏ.**

Ngưỡng "file to": file đang sửa >150 dòng HIỆN TẠI (đo trước khi sửa) HOẶC sửa >2 method/vị trí khác nhau trong cùng file → BẮT BUỘC dùng Serena MCP (`replace_symbol_body`, `insert_after_symbol`/`insert_before_symbol`, `safe_delete_symbol`, `replace_content`, `replace_in_files`) — không dùng `Edit`/`Write`.

File nhỏ (≤150 dòng) + edit đơn lẻ/độc lập (≤2 vị trí): tự do chọn `Edit` hoặc Serena `replace_content`.

File hoàn toàn mới → `Write` luôn hợp lệ bất kể kích thước (Serena không có thao tác tạo file mới).

Trong nhóm Serena (khi ngưỡng áp dụng):
1. 1–2 edit nhỏ độc lập trên file to → `replace_symbol_body`/`insert_*_symbol` hoặc `replace_content` đều ổn.
2. Sửa nhiều method / nhiều edit dịch dòng liên tiếp trong cùng file → ưu tiên `replace_content` (định vị bằng nội dung, không bị index drift — symbol-edit định vị bằng line range từ index, có thể lệch sau nhiều edit liên tiếp).
3. Sau chuỗi symbol-edit, nghi index lệch → verify qua `find_symbol`/`get_symbols_overview`/`codegraph_explore`, hoặc `get_diagnostics_for_file` (có thể stale — gradle build mới là chuẩn cuối).
4. Rewrite toàn bộ nội dung 1 file ĐÃ TỒN TẠI (to hay nhỏ) không tính là "file mới" — vẫn qua Serena `replace_content` nếu file to theo ngưỡng trên.

**Build & verify:** gradle build (`.\gradlew.bat build`, `JAVA_HOME` = JDK 25, xem memory `port-26-1-2-status`) là nguồn sự thật cuối cho lỗi biên dịch — không tin tuyệt đối LSP diagnostics.
