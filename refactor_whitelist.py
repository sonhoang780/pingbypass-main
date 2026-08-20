import sys

content = open('src/main/java/eu/client/gui/WhitelistEditorScreen.java', 'r', encoding='utf-8').read()

target = '''        String display = query.isEmpty() ? "Items (search...)" : query + (showLine ? "|" : " ");
        EUClient.FONT_MANAGER.drawTextWithShadow(context, display, x + 3, colY + 2, query.isEmpty() ? Color.GRAY : Color.WHITE);'''

replacement = '''        if (query.isEmpty()) {
            String placeholder = "Items (search...)";
            int cursorWidth = EUClient.FONT_MANAGER.getWidth(" ");
            if (showLine) {
                EUClient.FONT_MANAGER.drawTextWithShadow(context, "|", x + 3, colY + 2, Color.WHITE);
            }
            EUClient.FONT_MANAGER.drawTextWithShadow(context, placeholder, x + 3 + cursorWidth, colY + 2, Color.GRAY);
        } else {
            String display = query + (showLine ? "|" : " ");
            EUClient.FONT_MANAGER.drawTextWithShadow(context, display, x + 3, colY + 2, Color.WHITE);
        }'''

content = content.replace(target, replacement)

with open('src/main/java/eu/client/gui/WhitelistEditorScreen.java', 'w', encoding='utf-8') as f:
    f.write(content)
