package eu.client.modules.impl.player;

import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;

@RegisterModule(name = "MultiTask", description = "Allows you to interact with blocks while eating or using an item.", category = Module.Category.PLAYER)
public class MultiTaskModule extends Module {
    // Pearl: while eating, throwing a pearl with Phase / KeyAction restores your held slot in the
    // SAME tick so the server never spends a tick off your food slot -> the eat isn't cancelled.
    public BooleanSetting pearl = new BooleanSetting("Pearl", "Don't stop eating when you throw a pearl with Phase / KeyAction.", true);
    // AutoTotem: don't release/reset the item you're eating when AutoTotem swaps a totem to offhand.
    public BooleanSetting autoTotem = new BooleanSetting("AutoTotem", "Don't stop/reset your eating when AutoTotem swaps a totem into your offhand.", true);
}