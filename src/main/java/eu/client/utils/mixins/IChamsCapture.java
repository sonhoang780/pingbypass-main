package eu.client.utils.mixins;

// Carries ChamsModule's real fill+outline colors (independently, both at once -- unlike vanilla's
// single outlineColor field ShadersModule/PopChamsModule share) from EntityRendererMixin's
// extractRenderState hook through to ModelFeatureRendererMixin's flush-time quad capture. See
// ModelFeatureRendererMixin for why this needs to ride on the render state object itself: that's
// the only thing ModelFeatureRenderer.renderModel's SubmitNodeStorage.ModelSubmit still carries by
// the time geometry actually gets flushed to a real VertexConsumer.
public interface IChamsCapture {
    boolean euclient$chamsFill();
    int euclient$chamsFillColor();
    boolean euclient$chamsOutline();
    int euclient$chamsOutlineColor();
    boolean euclient$chamsShine();
    boolean euclient$chamsSuppressReal();

    void euclient$setChams(boolean fill, int fillColor, boolean outline, int outlineColor, boolean shine);
    void euclient$setChams(boolean fill, int fillColor, boolean outline, int outlineColor, boolean shine, boolean suppressReal);
}
