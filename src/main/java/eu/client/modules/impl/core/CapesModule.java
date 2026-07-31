package eu.client.modules.impl.core;

import lombok.Getter;
import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import net.minecraft.resources.Identifier;

@Getter
@RegisterModule(name = "Capes", description = "Applies the EUClient cape to yourself and to other users.", category = Module.Category.CORE, toggled = true, drawn = false)
public class CapesModule extends Module {
    public CapesModule() {
        // ClientAsset.ResourceTexture's single-arg constructor derives the real file path itself
        // ("textures/" + path + ".png") -- this needs the bare asset id (euclient:cape), not the
        // already-expanded texture path, or the derivation runs twice (textures/textures/cape.png.png,
        // a nonexistent file -> the pink/black missing-texture checkerboard).
        this.capeTexture = Identifier.fromNamespaceAndPath(EUClient.MOD_ID, "cape");
    }

    private final Identifier capeTexture;
}
