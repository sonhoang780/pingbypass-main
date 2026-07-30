package eu.client.modules.impl.miscellaneous;

import eu.client.EUClient;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.utils.graphics.Renderer2D;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.awt.*;

@RegisterModule(name = "ShulkerInfo", description = "Renders a preview of the contents of the shulker you are hovering.", category = Module.Category.MISCELLANEOUS)
public class ShulkerInfoModule extends Module {

    public void renderInfo(GuiGraphicsExtractor context, int x, int y, ItemStack itemStack) {
        try {
            ItemContainerContents component = itemStack.get(DataComponents.CONTAINER);
            Item item = itemStack.getItem();
            Color color = Color.WHITE;

            if(item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock shulker) {
                try {
                    color = new Color(shulker.getColor().getTextureDiffuseColor());
                } catch (Exception ignored) { }
            }
            x += 4;
            y -= 62 + EUClient.FONT_MANAGER.getHeight();

            Renderer2D.renderQuad(context, x, y, x + 164, y + 60 + EUClient.FONT_MANAGER.getHeight(), new Color(0, 0, 0, 200));
            Renderer2D.renderOutline(context, x, y, x + 164, y + 60 + EUClient.FONT_MANAGER.getHeight(), color);

            EUClient.FONT_MANAGER.drawTextWithShadow(context, itemStack.getHoverName().getString(), x + 3, y + 3, Color.WHITE);

            int row = 0, i = 0;
            for(ItemStack stack : component.nonEmptyItemCopyStream().toList()) {
                int offsetX = x + 1 + i * 18, offsetY = y + EUClient.FONT_MANAGER.getHeight() + 5 + row * 18;
                context.item(stack, offsetX, offsetY);
                context.itemDecorations(mc.font, stack, offsetX, offsetY);
                i++;
                if(i>=9) {
                    i = 0;
                    row++;
                }
            }
        } catch (Exception ignored) { }
    }

    public boolean hasItems(ItemStack itemStack) {
        ItemContainerContents component = itemStack.get(DataComponents.CONTAINER);
        return component != null && !component.nonEmptyItemCopyStream().toList().isEmpty();
    }
}
