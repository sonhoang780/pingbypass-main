package eu.client.utils.graphics.skia;

import io.github.humbleui.skija.Canvas;

import java.util.function.Consumer;

public record MusicHudPipState(Consumer<Canvas> painter, int x0, int y0, int x1, int y1) implements SkiaPaintedState {}
