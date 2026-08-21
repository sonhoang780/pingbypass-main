package eu.client.modules.impl.movement;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.mixins.accessors.CreativeInventoryScreenAccessor;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.NumberSetting;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

@RegisterModule(name = "ArrowControls", description = "Allows you to control the camera using your arrow keys.", category = Module.Category.MOVEMENT)
public class ArrowControlsModule extends Module {
    public NumberSetting speed = new NumberSetting("Speed", "The speed at which the camera will be moving at.", 1.0f, 0.1f, 10.0f);
    public BooleanSetting inventory = new BooleanSetting("Inventory", "Allows you to control the camera when a screen is opened.", true);

    private long lastTime;

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null && (!inventory.getValue() || (mc.gui.screen() instanceof ChatScreen || mc.gui.screen() instanceof BookEditScreen || mc.gui.screen() instanceof SignEditScreen || mc.gui.screen() instanceof JigsawBlockEditScreen || mc.gui.screen() instanceof StructureBlockEditScreen || mc.gui.screen() instanceof AnvilScreen || (mc.gui.screen() instanceof CreativeModeInventoryScreen && CreativeInventoryScreenAccessor.getSelectedTab().getType() == CreativeModeTab.Type.SEARCH))))
            return;

        float yaw = 0.0f;
        float pitch = 0.0f;

        float amount = ((System.currentTimeMillis() - lastTime) / 10f) * speed.getValue().floatValue();
        lastTime = System.currentTimeMillis();

        if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT)) yaw -= amount;
        if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT)) yaw += amount;
        if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_UP)) pitch -= amount;
        if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_DOWN)) pitch += amount;

        mc.player.setYRot(mc.player.getYRot() + yaw);
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitch, -90.0f, 90.0f));
    }

    @Override
    public String getMetaData() {
        return String.valueOf(speed.getValue().floatValue());
    }
}
