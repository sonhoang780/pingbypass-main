package eu.client.managers;

import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.PositionSetting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks the on-screen bounding box HUD elements were last drawn at (re-recorded every frame by
 * the elements themselves), plus a name -> (enable toggle, drag offset) mapping HUDEditorModule
 * reads to draw drag handles, hit-test clicks, and clamp offsets to stay on screen. Elements
 * register once (name, toggle, offset) and re-report their bounds each frame they render.
 *
 * Bounds are stored in RAW (pre-offset) space -- offset is applied on read, always from the
 * live PositionSetting. Baking the offset in at report-time (as an earlier version did) meant
 * clamp() had to "un-bake" a stale offset every drag tick, which drifted the moment the offset
 * changed faster than the once-per-frame report -- letting elements drag off-screen unclamped.
 */
public class HudElementRegistry {
    public record Element(String name, BooleanSetting enabled, PositionSetting offset) {}

    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();
    private static final Map<String, float[]> RAW_BOUNDS = new LinkedHashMap<>();

    public static void register(String name, BooleanSetting enabled, PositionSetting offset) {
        ELEMENTS.put(name, new Element(name, enabled, offset));
    }

    /** Called once per frame by an element right after it finishes drawing, in local (pre-offset) space. */
    public static void reportBounds(String name, float x0, float y0, float x1, float y1) {
        RAW_BOUNDS.put(name, new float[]{x0, y0, x1, y1});
    }

    public static Map<String, Element> getElements() {
        return ELEMENTS;
    }

    /** Current on-screen bounds (raw + live offset applied), or null if never reported. */
    public static float[] getBounds(String name) {
        float[] raw = RAW_BOUNDS.get(name);
        Element element = ELEMENTS.get(name);
        if (raw == null || element == null) return raw;

        float ox = element.offset().getX();
        float oy = element.offset().getY();
        return new float[]{raw[0] + ox, raw[1] + oy, raw[2] + ox, raw[3] + oy};
    }

    /** Clamps the element's offset so its last-known (raw) bounding box stays fully on screen. */
    public static void clamp(String name, int screenWidth, int screenHeight) {
        Element element = ELEMENTS.get(name);
        float[] raw = RAW_BOUNDS.get(name);
        if (element == null || raw == null) return;

        float width = raw[2] - raw[0];
        float height = raw[3] - raw[1];

        float minOffsetX = -raw[0];
        float maxOffsetX = Math.max(minOffsetX, screenWidth - width - raw[0]);
        float minOffsetY = -raw[1];
        float maxOffsetY = Math.max(minOffsetY, screenHeight - height - raw[1]);

        element.offset().set(
                Math.clamp(element.offset().getX(), minOffsetX, maxOffsetX),
                Math.clamp(element.offset().getY(), minOffsetY, maxOffsetY)
        );
    }
}
