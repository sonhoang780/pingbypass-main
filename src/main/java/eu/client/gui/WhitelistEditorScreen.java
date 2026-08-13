package eu.client.gui;

import eu.client.EUClient;
import eu.client.settings.impl.WhitelistSetting;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.minecraft.IdentifierUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// Client-wide whitelist editor popup, per user sketch: 2 columns styled like ClickGui's category
// Frames -- (1) the item list, doubling as the search box in its own header (typing filters the
// list in place, results sliding in left-to-right with ease-out deceleration), and (2) the current
// whitelist. Opened over the current screen (usually ClickGuiScreen) by WhitelistButton; closing
// restores it instead of going to null, same deferred-slide pattern as ClickGuiScreen.requestClose()/removed().
public class WhitelistEditorScreen extends Screen {
    private static final int COL_WIDTH = 150;
    private static final int COL_GAP = 10;
    private static final int HEADER_H = 13;
    private static final int ROW_H = 18;
    private static final int ICON_SIZE = 16;
    private static final int SLIDE_DURATION_MS = 320;
    private static final float SLIDE_DISTANCE = 160f;
    private static final int SEARCH_STAGGER_MS = 40;
    private static final int SEARCH_ROW_ANIM_MS = 220;
    private static final float SEARCH_SLIDE_DISTANCE = 40f;

    private final WhitelistSetting setting;
    private final Screen parent;
    private final List<Entry> allEntries;

    private String query = "";
    private String lastQuery = "";
    private long queryChangeTime = System.currentTimeMillis();

    private float scrollAll = 0, targetScrollAll = 0;
    private float scrollWhite = 0, targetScrollWhite = 0;

    private final Animation slideAnim = new Animation(SLIDE_DURATION_MS, Easing.Method.EASE_OUT_CUBIC);
    private boolean closing = false;

    // Blinking search-cursor -- own timer since ClickGuiScreen (and its lineTimer) isn't the
    // active screen while this popup is up, so EUClient.CLICK_GUI.isShowLine() would just freeze.
    private final eu.client.utils.system.Timer lineTimer = new eu.client.utils.system.Timer();
    private boolean showLine = false;

    private int colX1, colX2, colY, colHeight;

    public WhitelistEditorScreen(WhitelistSetting setting, Screen parent) {
        super(Component.literal(EUClient.MOD_ID + "-whitelist-editor"));
        this.setting = setting;
        this.parent = parent;
        this.allEntries = buildEntries(setting);
    }

    private static List<Entry> buildEntries(WhitelistSetting setting) {
        List<Entry> entries = new ArrayList<>();
        if (setting.getType() == WhitelistSetting.Type.BLOCKS) {
            BuiltInRegistries.BLOCK.entrySet().forEach(e -> {
                Block block = e.getValue();
                String id = e.getKey().identifier().toString();
                Item item = block.asItem();
                ItemStack icon = item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
                entries.add(new Entry(id, id.replace("minecraft:", ""), icon, block));
            });
        } else {
            BuiltInRegistries.ITEM.entrySet().forEach(e -> {
                Item item = e.getValue();
                String id = e.getKey().identifier().toString();
                entries.add(new Entry(id, id.replace("minecraft:", ""), new ItemStack(item), item));
            });
        }
        entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return entries;
    }

    public void requestClose() {
        closing = true;
        slideAnim.setEasing(Easing.Method.EASE_IN_CUBIC);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        Renderer2D.renderQuad(context, 0, 0, this.width, this.height, new Color(10, 8, 18, 150));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        if (lineTimer.hasTimeElapsed(400L)) {
            showLine = !showLine;
            lineTimer.reset();
        }

        float progress = slideAnim.get(closing ? 0f : 1f);
        if (closing && progress <= 0.001f) {
            Minecraft.getInstance().setScreen(parent);
            return;
        }

        int totalWidth = COL_WIDTH * 2 + COL_GAP;
        colX1 = (this.width - totalWidth) / 2;
        colX2 = colX1 + COL_WIDTH + COL_GAP;
        colY = 20;
        colHeight = this.height - 40;

        context.pose().pushMatrix();
        context.pose().translate(0, (1f - progress) * -SLIDE_DISTANCE);

        List<String> whitelistIds = setting.getWhitelistIds();
        List<Entry> whitelistEntries = allEntries.stream().filter(e -> whitelistIds.contains(e.id)).toList();
        // Already-whitelisted items are dropped from column 1 -- they read as "moved" into the
        // Whitelist column instead of showing up (highlighted) in both places at once.
        List<Entry> pool = allEntries.stream().filter(e -> !whitelistIds.contains(e.id)).toList();
        List<Entry> shown = query.isEmpty() ? pool : filter(pool, query);

        renderSearchColumn(context, mouseX, mouseY, colX1, shown, whitelistIds);
        renderColumn(context, mouseX, mouseY, colX2, "Whitelist (" + whitelistEntries.size() + ")", whitelistEntries, whitelistIds, scrollWhite, 2);

        context.pose().popMatrix();
    }

    private List<Entry> filter(List<Entry> source, String q) {
        String[] words = q.toLowerCase().split(" ");
        return source.stream().filter(e -> {
            String lower = e.name.toLowerCase();
            for (String w : words) if (!w.isEmpty() && !lower.contains(w)) return false;
            return true;
        }).toList();
    }

    private void renderColumn(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, String title, List<Entry> entries, List<String> whitelistIds, float scroll, int colIndex) {
        Renderer2D.renderQuad(context, x, colY, x + COL_WIDTH, colY + HEADER_H, new Color(20, 20, 25, 200));
        Renderer2D.renderQuad(context, x, colY + HEADER_H - 1, x + COL_WIDTH, colY + HEADER_H, ClickGuiScreen.getButtonColor(colY, 200));
        EUClient.FONT_MANAGER.drawTextWithShadow(context, title, x + 3, colY + 2, Color.WHITE);

        int bodyY = colY + HEADER_H;
        int bodyHeight = colHeight - HEADER_H;
        Renderer2D.renderQuad(context, x, bodyY, x + COL_WIDTH, bodyY + bodyHeight, new Color(15, 15, 20, 180));
        Renderer2D.renderOutline(context, x, colY, x + COL_WIDTH, bodyY + bodyHeight, ClickGuiScreen.getButtonColor(colY, 120));

        context.enableScissor(x, bodyY, x + COL_WIDTH, bodyY + bodyHeight);
        int rowsVisible = bodyHeight / ROW_H + 2;
        int firstIndex = (int) scroll;
        for (int i = 0; i < rowsVisible; i++) {
            int index = firstIndex + i;
            if (index < 0 || index >= entries.size()) continue;
            Entry entry = entries.get(index);
            int rowY = bodyY + Math.round(i * ROW_H - (scroll - firstIndex) * ROW_H);
            if (rowY + ROW_H < bodyY || rowY > bodyY + bodyHeight) continue;
            renderRow(context, mouseX, mouseY, x, rowY, entry, whitelistIds.contains(entry.id), colIndex);
        }
        context.disableScissor();
    }

    // Column 1's header doubles as the search box: typing filters this same list in place, and
    // whenever the query changes each visible row slides in from the left, staggered and
    // decelerating (EASE_OUT_CUBIC), instead of just popping to the new set.
    private void renderSearchColumn(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, List<Entry> results, List<String> whitelistIds) {
        Renderer2D.renderQuad(context, x, colY, x + COL_WIDTH, colY + HEADER_H, new Color(20, 20, 25, 200));
        Renderer2D.renderQuad(context, x, colY + HEADER_H - 1, x + COL_WIDTH, colY + HEADER_H, ClickGuiScreen.getButtonColor(colY, 200));
        String display = query.isEmpty() ? "Items (search...)" : query + (showLine ? "|" : " ");
        EUClient.FONT_MANAGER.drawTextWithShadow(context, display, x + 3, colY + 2, query.isEmpty() ? Color.GRAY : Color.WHITE);

        int bodyY = colY + HEADER_H;
        int bodyHeight = colHeight - HEADER_H;
        Renderer2D.renderQuad(context, x, bodyY, x + COL_WIDTH, bodyY + bodyHeight, new Color(15, 15, 20, 180));
        Renderer2D.renderOutline(context, x, colY, x + COL_WIDTH, bodyY + bodyHeight, ClickGuiScreen.getButtonColor(colY, 120));

        context.enableScissor(x, bodyY, x + COL_WIDTH, bodyY + bodyHeight);
        long now = System.currentTimeMillis();
        int rowsVisible = bodyHeight / ROW_H + 2;
        int firstIndex = (int) scrollAll;
        for (int i = 0; i < rowsVisible; i++) {
            int index = firstIndex + i;
            if (index < 0 || index >= results.size()) continue;
            Entry entry = results.get(index);
            int rowY = bodyY + Math.round(i * ROW_H - (scrollAll - firstIndex) * ROW_H);
            if (rowY + ROW_H < bodyY || rowY > bodyY + bodyHeight) continue;

            float delayed = (now - queryChangeTime) - index * SEARCH_STAGGER_MS;
            float t = Easing.ease(net.minecraft.util.Mth.clamp(delayed / SEARCH_ROW_ANIM_MS, 0f, 1f), Easing.Method.EASE_OUT_CUBIC);
            int slideX = Math.round((1f - t) * SEARCH_SLIDE_DISTANCE);

            context.pose().pushMatrix();
            context.pose().translate(-slideX, 0);
            renderRow(context, mouseX, mouseY, x, rowY, entry, whitelistIds.contains(entry.id), 1);
            context.pose().popMatrix();
        }
        context.disableScissor();
    }

    private void renderRow(GuiGraphicsExtractor context, int mouseX, int mouseY, int x, int rowY, Entry entry, boolean inWhitelist, int colIndex) {
        boolean hovering = mouseX >= x && mouseX < x + COL_WIDTH && mouseY >= rowY && mouseY < rowY + ROW_H - 1;
        Color bg = hovering ? new Color(255, 255, 255, 15) : (inWhitelist ? new Color(255, 255, 255, 8) : new Color(0, 0, 0, 0));
        if (bg.getAlpha() > 0) Renderer2D.renderQuad(context, x, rowY, x + COL_WIDTH, rowY + ROW_H - 1, bg);

        if (!entry.icon.isEmpty()) context.item(entry.icon, x + 2, rowY + (ROW_H - 1 - ICON_SIZE) / 2);
        Color nameColor = inWhitelist ? Color.YELLOW : Color.WHITE;
        EUClient.FONT_MANAGER.drawTextWithShadow(context, entry.name, x + ICON_SIZE + 6, rowY + (ROW_H - 1) / 2 - EUClient.FONT_MANAGER.getHeight() / 2, nameColor);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);

        List<String> whitelistIds = setting.getWhitelistIds();
        int bodyY = colY + HEADER_H;
        int bodyHeight = colHeight - HEADER_H;
        if (mouseY < bodyY || mouseY >= bodyY + bodyHeight) return super.mouseClicked(event, doubleClick);

        if (mouseX >= colX1 && mouseX < colX1 + COL_WIDTH) {
            List<Entry> pool = allEntries.stream().filter(e -> !whitelistIds.contains(e.id)).toList();
            List<Entry> shown = query.isEmpty() ? pool : filter(pool, query);
            Entry clicked = pickRow(mouseX, mouseY, bodyY, scrollAll, shown);
            if (clicked != null) addToWhitelist(clicked);
            return true;
        }
        if (mouseX >= colX2 && mouseX < colX2 + COL_WIDTH) {
            List<Entry> whitelistEntries = allEntries.stream().filter(e -> whitelistIds.contains(e.id)).toList();
            Entry clicked = pickRow(mouseX, mouseY, bodyY, scrollWhite, whitelistEntries);
            if (clicked != null) removeFromWhitelist(clicked);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private Entry pickRow(double mouseX, double mouseY, int bodyY, float scroll, List<Entry> entries) {
        int relativeY = (int) (mouseY - bodyY);
        int index = (int) scroll + relativeY / ROW_H;
        if (index < 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    private void addToWhitelist(Entry entry) {
        if (setting.getType() == WhitelistSetting.Type.BLOCKS) setting.add(IdentifierUtils.getBlock(entry.id));
        else setting.add(IdentifierUtils.getItem(entry.id));
    }

    private void removeFromWhitelist(Entry entry) {
        if (setting.getType() == WhitelistSetting.Type.BLOCKS) setting.remove(IdentifierUtils.getBlock(entry.id));
        else setting.remove(IdentifierUtils.getItem(entry.id));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= colX1 && mouseX < colX1 + COL_WIDTH) {
            List<String> whitelistIds = setting.getWhitelistIds();
            List<Entry> pool = allEntries.stream().filter(e -> !whitelistIds.contains(e.id)).toList();
            List<Entry> shown = query.isEmpty() ? pool : filter(pool, query);
            targetScrollAll = clampScroll(targetScrollAll - (float) verticalAmount, shown.size());
        } else if (mouseX >= colX2 && mouseX < colX2 + COL_WIDTH) {
            targetScrollWhite = clampScroll(targetScrollWhite - (float) verticalAmount, setting.getWhitelistIds().size());
        }
        scrollAll += (targetScrollAll - scrollAll) * 0.5f;
        scrollWhite += (targetScrollWhite - scrollWhite) * 0.5f;
        return true;
    }

    private float clampScroll(float value, int entryCount) {
        int bodyHeight = colHeight - HEADER_H;
        float maxScroll = Math.max(0, entryCount - (float) bodyHeight / ROW_H);
        return Math.max(0, Math.min(value, maxScroll));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            requestClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            setQuery(query.substring(0, query.length() - 1));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        if (!Character.isISOControl(chr)) {
            setQuery(query + chr);
            return true;
        }
        return super.charTyped(event);
    }

    private void setQuery(String newQuery) {
        if (!newQuery.equals(lastQuery)) {
            queryChangeTime = System.currentTimeMillis();
            lastQuery = newQuery;
            // Old scroll offset is meaningless against the new filtered set -- without this a
            // deep scroll position could sit past the end of a short result list, hiding every row.
            scrollAll = 0;
            targetScrollAll = 0;
        }
        query = newQuery;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class Entry {
        final String id, name;
        final ItemStack icon;

        Entry(String id, String name, ItemStack icon, Object registryObject) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }
}
