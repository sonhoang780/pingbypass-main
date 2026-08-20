package eu.client.utils.graphics.skia;

import net.minecraft.client.renderer.MultiBufferSource;

public final class MusicHudPipRenderer extends AbstractSkiaPipRenderer<MusicHudPipState> {

    public static volatile MusicHudPipRenderer ACTIVE;

    public MusicHudPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<MusicHudPipState> getRenderStateClass() { return MusicHudPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_musichud"; }
}
