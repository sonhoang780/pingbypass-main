package eu.client.mixins;

import eu.client.utils.mixins.IChamsCapture;
import eu.client.utils.mixins.ISelfState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements IChamsCapture, ISelfState {
    @Unique private boolean chamsFill = false;
    @Unique private int chamsFillColor = 0;
    @Unique private boolean chamsOutline = false;
    @Unique private int chamsOutlineColor = 0;
    @Unique private boolean chamsShine = false;
    @Unique private boolean isSelf = false;

    @Override
    public boolean euclient$isSelf() {
        return isSelf;
    }

    @Override
    public void euclient$setSelf(boolean self) {
        this.isSelf = self;
    }

    @Override
    public boolean euclient$chamsFill() {
        return chamsFill;
    }

    @Override
    public int euclient$chamsFillColor() {
        return chamsFillColor;
    }

    @Override
    public boolean euclient$chamsOutline() {
        return chamsOutline;
    }

    @Override
    public int euclient$chamsOutlineColor() {
        return chamsOutlineColor;
    }

    @Override
    public boolean euclient$chamsShine() {
        return chamsShine;
    }

    @Override
    public void euclient$setChams(boolean fill, int fillColor, boolean outline, int outlineColor, boolean shine) {
        this.chamsFill = fill;
        this.chamsFillColor = fillColor;
        this.chamsOutline = outline;
        this.chamsOutlineColor = outlineColor;
        this.chamsShine = shine;
    }
}
