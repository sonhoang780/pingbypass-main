package eu.client.gui.api;

import java.util.List;

// Shared contract between ModuleButton and any other "row that expands into a scrollable
// sub-list of settings" Frame needs to lay out and animate identically -- HudElementButton
// (HUDEditor's settings column) is the other implementer. Frame used to hardcode
// `instanceof ModuleButton` at every layout/scroll site; this interface lets it drive any
// expandable row the same way (same open/close animation, same reveal-height scissor clip,
// same scroll handling) without HudElementButton having to fake being a ModuleButton (which
// requires a real Module -- HUD elements aren't modules).
public interface ExpandableRow {
    /** Name this row's own search filter (if any) and label rendering key off. */
    String getRowName();

    boolean isOpen();

    /** Current 0..1 open/close animation progress (see ModuleButton.getOpenAmount()'s own doc). */
    float getOpenAmount();

    /** The row's own child setting buttons, laid out beneath it while open. */
    List<Button> getButtons();

    /** Pixel height Frame is currently willing to reveal of this row's children (animated). */
    void setRevealHeight(int height);

    void setSearchQuery(String query);
}
