package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

@Getter @AllArgsConstructor
public class RenderOverlayEvent extends Event {
    private final GuiGraphicsExtractor context;
    private final float tickDelta;

    public Matrix3x2fStack getMatrices() {
        return context.pose();
    }
}
