package eu.client.utils.mixins;

// Plain, non-@Mixin holder for state shared between ItemInHandRendererMixin and
// OutlineBufferSourceMixin. Sponge Mixin rejects ANY non-private member on a @Mixin class itself
// (package-private/default access still counts as "non-private", same crash as public did) --
// AND separately, mixins.json's "package": "eu.client.mixins" claims that ENTIRE package
// exclusively for mixin classes (IllegalClassLoadError if a plain class sits in there too). So a
// flag two mixin classes both need has to live in a genuinely different, non-mixin-owned package.
public final class HandsRenderState {
    public static boolean renderingHands = false;

    private HandsRenderState() {}
}
