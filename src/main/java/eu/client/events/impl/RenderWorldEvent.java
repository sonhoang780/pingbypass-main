package eu.client.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eu.client.events.Event;
import com.mojang.blaze3d.vertex.PoseStack;

@Getter @AllArgsConstructor
public class RenderWorldEvent extends Event {
    private final PoseStack matrices;
    private final float tickDelta;

    @Getter @AllArgsConstructor
    public static class Post extends Event {
        private final PoseStack matrices;
        private final float tickDelta;
    }
}
