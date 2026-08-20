import sys

content = open('src/main/java/eu/client/gui/impl/WhitelistButton.java', 'r', encoding='utf-8').read()

target = '''            } else if (displayedSearch.isEmpty()) {
                displayedSearch = "Search...";
            }

            EUClient.FONT_MANAGER.drawTextWithShadow(context, displayedSearch, getX() + getTextPadding() + 2 + offsetX, contentY + (searchBarHeight / 2) - (EUClient.FONT_MANAGER.getHeight() / 2), searching ? (selecting ? ClickGuiScreen.getButtonColor(getY(), 255) : Color.WHITE) : Color.GRAY);'''

replacement = '''            } else if (displayedSearch.isEmpty()) {
                displayedSearch = "Search...";
            }

            if (searching && searchQuery.isEmpty()) {
                String cursorChar = EUClient.CLICK_GUI.isShowLine() ? "|" : " ";
                EUClient.FONT_MANAGER.drawTextWithShadow(context, cursorChar, getX() + getTextPadding() + 2 + offsetX, contentY + (searchBarHeight / 2) - (EUClient.FONT_MANAGER.getHeight() / 2), Color.WHITE);
                EUClient.FONT_MANAGER.drawTextWithShadow(context, "Search...", getX() + getTextPadding() + 2 + offsetX + EUClient.FONT_MANAGER.getWidth(" "), contentY + (searchBarHeight / 2) - (EUClient.FONT_MANAGER.getHeight() / 2), Color.GRAY);
            } else {
                EUClient.FONT_MANAGER.drawTextWithShadow(context, displayedSearch, getX() + getTextPadding() + 2 + offsetX, contentY + (searchBarHeight / 2) - (EUClient.FONT_MANAGER.getHeight() / 2), searching ? (selecting ? ClickGuiScreen.getButtonColor(getY(), 255) : Color.WHITE) : Color.GRAY);
            }'''

content = content.replace(target, replacement)

with open('src/main/java/eu/client/gui/impl/WhitelistButton.java', 'w', encoding='utf-8') as f:
    f.write(content)
