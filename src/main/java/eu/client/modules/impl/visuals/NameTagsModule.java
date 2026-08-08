package eu.client.modules.impl.visuals;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.modules.impl.miscellaneous.FakePlayerModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.text.CustomFormatting;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Holder;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;

@RegisterModule(name = "NameTags", description = "Replaces the default Minecraft NameTag with a more visible and customizable one.", category = Module.Category.VISUALS)
public class NameTagsModule extends Module {
    public BooleanSetting gameMode = new BooleanSetting("GameMode", "Renders the player's gamemode.", false);
    public BooleanSetting ping = new BooleanSetting("Ping", "Renders the player's latency to the server.", true);
    public BooleanSetting entityId = new BooleanSetting("EntityID", "Renders the player's entity ID.", false);
    public BooleanSetting health = new BooleanSetting("Health", "Renders the player's health and absorption.", true);
    public BooleanSetting totemPops = new BooleanSetting("TotemPops", "Renders the amount of totems that the player has popped.", true);
    public BooleanSetting antiBot = new BooleanSetting("AntiBot", "Prevents bots from having nametags rendered for them.", false);
    public BooleanSetting euCheck = new BooleanSetting("EUCheck", "Adds an indicator next to the name of other users..", true);
    public BooleanSetting self = new BooleanSetting("Self", "Also renders a nametag above your own player.", false);

    public BooleanSetting items = new BooleanSetting("Items", "Renders the items that the player is wearing or holding.", true);
    public BooleanSetting enchantments = new BooleanSetting("Enchantments", "Renders the enchantments of the player's items.", false);
    public BooleanSetting durability = new BooleanSetting("Durability", "Renders the durability of the player's items.", true);
    public BooleanSetting itemName = new BooleanSetting("ItemName", "Renders the name of the item that the player is currently holding.", true);

    public NumberSetting scale = new NumberSetting("Scale", "The scaling that will be applied to the nametag rendering.", 30, 10, 100);
    public ModeSetting border = new ModeSetting("Border", "The border that will surround the text.", "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting fillColor = new ColorSetting("FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(border, "Fill", "Both"), new ColorSetting.Color(new Color(0, 0, 0, 100), false, false));
    public ColorSetting outlineColor = new ColorSetting("OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(border, "Outline", "Both"), new ColorSetting.Color(new Color(0, 0, 0, 100), false, false));

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent.Post event) {
        PoseStack matrices = event.getMatrices();
        MultiBufferSource.BufferSource vertexConsumers = mc.renderBuffers().bufferSource();

        for (Player player : mc.level.players().stream().sorted(Comparator.comparing(p -> -mc.player.distanceTo(p))).toList()) {
            // First person puts the camera basically AT this anchor (head + ~2 blocks) -- pitching
            // up swings the camera's forward vector straight through the near-degenerate billboard
            // and flashes the tag into view right in front of the lens. Self only makes sense in
            // 3rd person (where you can actually see your own head to look "at") -- EXCEPT Freecam
            // detaches the actual camera position without touching mc.options.getCameraType()
            // (still reports FIRST_PERSON), so that check alone wrongly hid self's nametag even
            // while Freecam had the camera floating well away from the degenerate near-anchor case.
            boolean freecam = EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled();
            if (player == mc.player && (!self.getValue() || (mc.options.getCameraType().isFirstPerson() && !freecam))) continue;
            if (antiBot.getValue() && EntityUtils.isBot(player)) continue;
            if (!Renderer3D.isFrustumVisible(player.getBoundingBox())) continue;

            double x = Mth.lerp(event.getTickDelta(), player.xo, player.getX());
            double y = Mth.lerp(event.getTickDelta(), player.yo, player.getY()) + (player.isShiftKeyDown() ? 1.9f : 2.1f);
            double z = Mth.lerp(event.getTickDelta(), player.zo, player.getZ());

            Vec3 vec3d = new Vec3(x - mc.getEntityRenderDispatcher().camera.position().x, y - mc.getEntityRenderDispatcher().camera.position().y, z - mc.getEntityRenderDispatcher().camera.position().z);
            float distance = (float) Math.sqrt(mc.getEntityRenderDispatcher().camera.position().distanceToSqr(x, y, z));
            float scaling = 0.0018f + (scale.getValue().intValue() / 10000.0f) * distance;
            if (distance <= 8.0) scaling = 0.0245f;

            matrices.pushPose();
            matrices.translate(vec3d.x, vec3d.y, vec3d.z);
            matrices.mulPose(mc.getEntityRenderDispatcher().camera.rotation());
            matrices.scale(scaling, -scaling, scaling);

            String text = player.getName().getString();
            if (gameMode.getValue()) text += " [" + EntityUtils.getGameModeName(EntityUtils.getGameMode(player)) + "]";
            if (ping.getValue()) text += " " + EntityUtils.getLatency(player) + "ms";
            if (entityId.getValue()) text += " " + player.getId();
            if (health.getValue()) text += " " + ColorUtils.getHealthColor(player.getHealth() + player.getAbsorptionAmount()) + new DecimalFormat("0.0").format(player.getHealth() + player.getAbsorptionAmount()) + ChatFormatting.RESET;

            int pops = EUClient.WORLD_MANAGER.getPoppedTotems().getOrDefault(player.getUUID(), 0);
            if (totemPops.getValue() && pops > 0) text += " " + ColorUtils.getTotemColor(pops) + "-" + pops;

            int width = EUClient.FONT_MANAGER.getWidth(text);

            if (border.getValue().equalsIgnoreCase("Fill") || border.getValue().equalsIgnoreCase("Both")) Renderer3D.renderQuad(matrices, -width / 2.0f - 1, -EUClient.FONT_MANAGER.getHeight() - 1, width / 2.0f + 2, 0, fillColor.getColor());
            if (border.getValue().equalsIgnoreCase("Outline") || border.getValue().equalsIgnoreCase("Both")) Renderer3D.renderOutline(matrices, -width / 2.0f - 1, -EUClient.FONT_MANAGER.getHeight() - 1, width / 2.0f + 2, 0, outlineColor.getColor());

            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -width / 2, -EUClient.FONT_MANAGER.getHeight(), vertexConsumers, EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).getPlayer() == player ? new Color(225, 0, 70) : player.isShiftKeyDown() ? new Color(255, 170, 0) : EUClient.FRIEND_MANAGER.contains(player.getName().getString()) ? EUClient.FRIEND_MANAGER.getDefaultFriendColor() : Color.WHITE);

            boolean renderedDurability = false;
            boolean renderedItems = false;
            int maxEnchants = 0;

            if (enchantments.getValue()) {
                for (int i = 0; i < 6; i++) {
                    ItemStack stack = getItem(player, i);
                    ItemEnchantments component = EnchantmentHelper.getEnchantmentsForCrafting(stack);

                    if (!component.keySet().isEmpty()) {
                        int height = (component.keySet().size() * EUClient.FONT_MANAGER.getHeight() / 2) - 18;
                        if (height > 0 && (height + 1) > maxEnchants) maxEnchants = height + 1;
                    }
                }
            }

            for (int i = 0; i < 6; i++) {
                ItemStack stack = getItem(player, i);
                if (stack.isEmpty()) continue;

                renderedItems = true;

                int stackX = -(108 / 2) + (i * 18) + 1;
                int stackY = -EUClient.FONT_MANAGER.getHeight() - 1 - (items.getValue() ? 18 + maxEnchants : 1);

                if (items.getValue()) {
                    matrices.pushPose();
                    matrices.translate(stackX + 8, stackY + 8, 0);
                    matrices.scale(16, -16, -0.001f);
                    Renderer3D.renderItem(matrices, stack, player, vertexConsumers);
                    matrices.popPose();

                    if (stack.getItem().equals(Items.ENCHANTED_GOLDEN_APPLE)) {
                        matrices.pushPose();
                        matrices.translate(stackX, stackY, 0);
                        matrices.scale(0.5f, 0.5f, 1);
                        EUClient.FONT_MANAGER.drawTextWithShadow(matrices, "God", 0, 0, vertexConsumers, new Color(255, 125, 255));
                        matrices.popPose();
                    }

                    if (stack.getCount() != 1) {
                        String count = stack.getCount() + "";
                        matrices.pushPose();
                        matrices.translate(stackX + 17 - EUClient.FONT_MANAGER.getWidth(count), stackY + 9, 0);
                        EUClient.FONT_MANAGER.drawTextWithShadow(matrices, count, 0, 0, vertexConsumers, Color.WHITE);
                        matrices.popPose();
                    }
                }

                if (durability.getValue() && stack.isDamageableItem()) {
                    float green = (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
                    float red = 1.0f - green;

                    matrices.pushPose();
                    matrices.translate(stackX, stackY - EUClient.FONT_MANAGER.getHeight() / 2f - 1, 0);
                    matrices.scale(0.5f, 0.5f, 1);
                    EUClient.FONT_MANAGER.drawTextWithShadow(matrices, Math.round(((stack.getMaxDamage() - stack.getDamageValue()) * 100.0f) / stack.getMaxDamage()) + "%", 0, 0, vertexConsumers, new Color(red, green, 0));
                    matrices.popPose();

                    renderedDurability = true;
                }

                if (items.getValue() && enchantments.getValue() && EnchantmentHelper.hasAnyEnchantments(stack)) {
                    ItemEnchantments component = EnchantmentHelper.getEnchantmentsForCrafting(stack);
                    Object2IntMap<Holder<Enchantment>> enchantments = new Object2IntOpenHashMap<>();
                    for (Holder<Enchantment> enchantment : component.keySet()) {
                        enchantments.put(enchantment, component.getLevel(enchantment));
                    }

                    int height = 0;
                    for (Object2IntMap.Entry<Holder<Enchantment>> entry : Object2IntMaps.fastIterable(enchantments)) {
                        String str = getEnchantmentName(entry.getKey().getRegisteredName(), entry.getIntValue());

                        matrices.pushPose();
                        matrices.translate(stackX, stackY + height, 0);
                        matrices.scale(0.5f, 0.5f, 1);
                        EUClient.FONT_MANAGER.drawTextWithShadow(matrices, str, 0, 0, vertexConsumers, Color.WHITE);
                        matrices.popPose();

                        height += EUClient.FONT_MANAGER.getHeight() / 2;
                    }
                }
            }

            if (itemName.getValue() && !player.getMainHandItem().isEmpty()) {
                String itemText = player.getMainHandItem().getHoverName().getString();

                matrices.pushPose();
                matrices.translate(-EUClient.FONT_MANAGER.getWidth(itemText) / 2f / 2f, -EUClient.FONT_MANAGER.getHeight() - 1 - EUClient.FONT_MANAGER.getHeight() / 2f - 1 - (renderedItems ? (items.getValue() ? 18 + maxEnchants : 1) + (durability.getValue() && renderedDurability ? EUClient.FONT_MANAGER.getHeight() / 2.0f + 1 : 0) : 0), 0);
                matrices.scale(0.5f, 0.5f, 1);
                EUClient.FONT_MANAGER.drawTextWithShadow(matrices, itemText, 0, 0, vertexConsumers, Color.WHITE);
                matrices.popPose();
            }

            matrices.popPose();

            // Renderer3D.QUADS/DEBUG_LINES (used by the border Fill/Outline above) is ONE shared,
            // frame-global, no-depth-test list -- everything queued into it this frame draws
            // together in a SINGLE call, in insertion order, painter's-algorithm style (last in
            // wins wherever boxes overlap on screen). Self's nametag sits right where the 3rd-
            // person camera pivots, so it's far more likely to screen-overlap a nearby player's
            // own nametag than anyone else's ever is -- and sharing that one draw call is what let
            // the two boxes bleed into each other (one losing its background, showing the other's
            // instead). Flushing+clearing right after each player's own box guarantees it draws as
            // its own isolated call, so it can never blend with the next player's.
            Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
            Renderer3D.QUADS.clear();
            Renderer3D.DEBUG_LINES.clear();
        }
    }

    private ItemStack getItem(Player player, int index) {
        return switch (index) {
            case 0 -> player.getMainHandItem();
            case 1 -> player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            case 2 -> player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            case 3 -> player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS);
            case 4 -> player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
            case 5 -> player.getOffhandItem();
            default -> ItemStack.EMPTY;
        };
    }

    private String getEnchantmentName(String id, int level) {
        id = id.replace("minecraft:", "");
        id = level > 1 ? id.substring(0, 2) : id.substring(0, 3);
        return id.substring(0, 1).toUpperCase() + id.substring(1) + " " + (level > 1 ? level : "");
    }

    private record ItemElement(ItemStack stack, List<String> enchantments) { }
}