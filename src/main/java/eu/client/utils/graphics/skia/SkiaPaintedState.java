package eu.client.utils.graphics.skia;

import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

import java.util.function.Consumer;

public interface SkiaPaintedState extends PictureInPictureRenderState {
    Consumer<Canvas> painter();
    int x0();
    int y0();
    int x1();
    int y1();

    @Override
    default float scale() { return 1.0f; }

    @Override
    default ScreenRectangle scissorArea() { return null; }

    @Override
    default ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0(), y0(), x1(), y1(), null);
    }
}
