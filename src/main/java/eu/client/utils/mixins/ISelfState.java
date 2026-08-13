package eu.client.utils.mixins;

// Carries "is this render state the local player's" from EntityRendererMixin's extractRenderState
// (which has the real entity, T entity == mc.player) through to LivingEntityRendererMixin's submit
// (which only gets the render state, no entity param) -- same ride-on-the-state-object pattern as
// IChamsCapture, for the same reason (nothing else survives that far in the pipeline).
public interface ISelfState {
    boolean euclient$isSelf();
    void euclient$setSelf(boolean self);
}
