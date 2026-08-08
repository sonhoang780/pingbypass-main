package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.TickEvent;
import eu.client.mixins.accessors.CreativeInventoryScreenAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.CreativeModeTab;

@RegisterModule(name = "InventoryControl", description = "Allows you to control things such as movement and your camera while a GUI is open.", category = Module.Category.MOVEMENT)
public class InventoryControlModule extends Module {
    public BooleanSetting movement = new BooleanSetting("Movement", "Allows you to control movement.", true);
    public BooleanSetting portals = new BooleanSetting("Portals", "Allows you to interact with GUIs while inside of a portal.", true);
    // Hold shift + left-click and drag across slots to shift-click (quick-move) every item the
    // cursor passes over, instead of having to shift-click each slot individually. Handled in
    // HandledScreenMixin -- this setting is just the toggle it reads.
    public BooleanSetting dragClick = new BooleanSetting("DragClick", "Hold shift + left-click and drag over slots to move each item you pass over.", true);

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null) return;

        if (movement.getValue() && mc.screen != null && !(mc.screen instanceof ChatScreen || mc.screen instanceof BookEditScreen || mc.screen instanceof SignEditScreen || mc.screen instanceof JigsawBlockEditScreen || mc.screen instanceof StructureBlockEditScreen || mc.screen instanceof AnvilScreen || (mc.screen instanceof CreativeModeInventoryScreen && CreativeInventoryScreenAccessor.getSelectedTab().getType() == CreativeModeTab.Type.SEARCH))) {
            for (KeyMapping binding : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyRight, mc.options.keyLeft, mc.options.keySprint, mc.options.keyShift, mc.options.keyJump}) {
                binding.setDown(InputConstants.isKeyDown(mc.getWindow(), InputConstants.getKey(binding.saveString()).getValue()));
            }
        }
    }
}
