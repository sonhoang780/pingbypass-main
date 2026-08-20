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
        if (mc.level == null || mc.player == null) return;

        PoseStack matrices = event.getMatrices();
        MultiBufferSource.BufferSource vertexConsumers = mc.renderBuffers().bufferSource();

        for (Player player : mc.level.players().stream().sorted(Comparator.comparing(p -> -mc.player.distanceTo(p))).toList()) {
            boolean freecam = EUClient.MODULE_MANAGER.getModule(FreecamModule.class).isToggled();
            if (player == mc.player && (!self.getValue() || (mc.options.getCameraType().isFirstPerson() && !freecam))) continue;
            if (antiBot.getValue() && EntityUtils.isBot(player)) continue;
            if (player instanceof net.minecraft.client.player.RemotePlayer ghost
                    && ((EUClient.MODULE_MANAGER.getModule(PopChamsModule.class) != null && EUClient.MODULE_MANAGER.getModule(PopChamsModule.class).isGhost(ghost))
                    || (EUClient.MODULE_MANAGER.getModule(LogoutSpotModule.class) != null && EUClient.MODULE_MANAGER.getModule(LogoutSpotModule.class).isGhost(ghost)))) continue;
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
            int fontHeight = EUClient.FONT_MANAGER.getHeight();

            // 1. Khung viền và nền nametag ở đáy
            if (border.getValue().equalsIgnoreCase("Fill") || border.getValue().equalsIgnoreCase("Both")) 
                Renderer3D.renderQuad(matrices, -width / 2.0f - 2, -fontHeight - 1, width / 2.0f + 2, 1, fillColor.getColor());
            if (border.getValue().equalsIgnoreCase("Outline") || border.getValue().equalsIgnoreCase("Both")) 
                Renderer3D.renderOutline(matrices, -width / 2.0f - 2, -fontHeight - 1, width / 2.0f + 2, 1, outlineColor.getColor());

            // 2. Chữ tên người chơi
            Color nameColor = EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).isToggled() && EUClient.MODULE_MANAGER.getModule(FakePlayerModule.class).getPlayer() == player ? new Color(225, 0, 70) : player.isShiftKeyDown() ? new Color(255, 170, 0) : EUClient.FRIEND_MANAGER.contains(player.getName().getString()) ? EUClient.FRIEND_MANAGER.getDefaultFriendColor() : Color.WHITE;
            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, text, -width / 2, -fontHeight, vertexConsumers, nameColor);

            float highestY = -fontHeight - 2;

            // 3. Render cụm Items (Icons + Durability + Stack Count + Enchantments)
            if (items.getValue()) {
                boolean hasAnyItem = false;
                for (int i = 0; i < 6; i++) {
                    if (!getItem(player, i).isEmpty()) {
                        hasAnyItem = true;
                        break;
                    }
                }

                if (hasAnyItem) {
                    // Tính toán chiều cao tối đa của các cột enchantments để nâng vị trí itemRowY lên vừa vặn
                    float maxEnchantsHeight = 0;
                    if (enchantments.getValue()) {
                        for (int i = 0; i < 6; i++) {
                            ItemStack stack = getItem(player, i);
                            if (stack.isEmpty() || !EnchantmentHelper.hasAnyEnchantments(stack)) continue;
                            ItemEnchantments component = EnchantmentHelper.getEnchantmentsForCrafting(stack);
                            int count = component.keySet().size();
                            float h = count * 4.2f;
                            if (h > maxEnchantsHeight) maxEnchantsHeight = h;
                        }
                    }

                    float itemContentHeight = Math.max(16.0f, maxEnchantsHeight);
                    // itemRowY được nâng lên cao để khi enchantments ghi xuống dưới không bị đè vào NameTag
                    float itemRowY = -fontHeight - 4.0f - itemContentHeight;
                    highestY = itemRowY;

                    // BƯỚC A: Vẽ tất cả Icon Item trước (Z = -0.5f lùi ra sau)
                    for (int i = 0; i < 6; i++) {
                        ItemStack stack = getItem(player, i);
                        if (stack.isEmpty()) continue;

                        int stackX = -(108 / 2) + (i * 18) + 1;

                        matrices.pushPose();
                        matrices.translate(stackX + 8, itemRowY + 8, -0.5f);
                        matrices.scale(16, -16, 0.0001f);
                        Renderer3D.renderItem(matrices, stack, player, vertexConsumers);
                        matrices.popPose();
                    }

                    // Flush ngay lập tức layer 3D của các item để text luôn nổi lên trên
                    vertexConsumers.endBatch();

                    // BƯỚC B: Vẽ toàn bộ Text đè lên trên Item (Z = 1.0f)
                    for (int i = 0; i < 6; i++) {
                        ItemStack stack = getItem(player, i);
                        if (stack.isEmpty()) continue;

                        int stackX = -(108 / 2) + (i * 18) + 1;

                        // 1. Độ bền giáp / vũ khí (% Durability) - Nằm ngay TRÊN đỉnh của Item
                        if (durability.getValue() && stack.isDamageableItem()) {
                            float green = (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
                            float red = 1.0f - green;
                            String durText = Math.round(((stack.getMaxDamage() - stack.getDamageValue()) * 100.0f) / stack.getMaxDamage()) + "%";
                            float durWidth = EUClient.FONT_MANAGER.getWidth(durText) * 0.5f;

                            matrices.pushPose();
                            matrices.translate(stackX + 8 - (durWidth / 2f), itemRowY - 5.5f, 1.0f);
                            matrices.scale(0.5f, 0.5f, 1);
                            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, durText, 0, 0, vertexConsumers, new Color(red, green, 0));
                            matrices.popPose();
                        }

                        // 2. Nhãn Gapple "God" (đè góc trên của táo vàng)
                        if (stack.getItem().equals(Items.ENCHANTED_GOLDEN_APPLE)) {
                            matrices.pushPose();
                            matrices.translate(stackX + 1, itemRowY + 1, 1.0f);
                            matrices.scale(0.5f, 0.5f, 1);
                            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, "God", 0, 0, vertexConsumers, new Color(255, 125, 255));
                            matrices.popPose();
                        }

                        // 3. Số lượng item (Stack Count - đè góc dưới phải)
                        if (stack.getCount() != 1) {
                            String count = String.valueOf(stack.getCount());
                            float countWidth = EUClient.FONT_MANAGER.getWidth(count) * 0.5f;
                            matrices.pushPose();
                            matrices.translate(stackX + 16 - countWidth, itemRowY + 12.0f, 1.0f);
                            matrices.scale(0.5f, 0.5f, 1);
                            EUClient.FONT_MANAGER.drawTextWithShadow(matrices, count, 0, 0, vertexConsumers, Color.WHITE);
                            matrices.popPose();
                        }

                        // 4. Enchantments: Bắt đầu cùng vị trí item và ghi XUỐNG DƯỚI, khoảng cách dòng sát nhau
                        if (enchantments.getValue() && EnchantmentHelper.hasAnyEnchantments(stack)) {
                            ItemEnchantments component = EnchantmentHelper.getEnchantmentsForCrafting(stack);
                            Object2IntMap<Holder<Enchantment>> enchMap = new Object2IntOpenHashMap<>();
                            for (Holder<Enchantment> enchantment : component.keySet()) {
                                enchMap.put(enchantment, component.getLevel(enchantment));
                            }

                            int enchIndex = 0;
                            float enchLineStep = 4.2f; // Khoảng cách dòng sít gần nhau

                            for (Object2IntMap.Entry<Holder<Enchantment>> entry : Object2IntMaps.fastIterable(enchMap)) {
                                String str = getEnchantmentName(entry.getKey().getRegisteredName(), entry.getIntValue());
                                float enchY = itemRowY + 8.0f + (enchIndex * enchLineStep);

                                matrices.pushPose();
                                matrices.translate(stackX + 1, enchY, 1.0f);
                                matrices.scale(0.5f, 0.5f, 1);
                                EUClient.FONT_MANAGER.drawTextWithShadow(matrices, str, 0, 0, vertexConsumers, Color.WHITE);
                                matrices.popPose();

                                enchIndex++;
                            }
                        }
                    }

                    // Flush text của item
                    vertexConsumers.endBatch();

                    // Cập nhật đỉnh cao nhất cho ItemName
                    if (durability.getValue()) {
                        highestY = itemRowY - 6.0f;
                    }
                }
            }

            // 4. Vẽ Tên Item đang cầm (ItemName) ở đỉnh cao nhất
            if (itemName.getValue() && !player.getMainHandItem().isEmpty()) {
                String itemText = player.getMainHandItem().getHoverName().getString();
                float itemTextWidth = EUClient.FONT_MANAGER.getWidth(itemText) * 0.5f;
                float nameY = highestY - (fontHeight * 0.5f) - 2;

                matrices.pushPose();
                matrices.translate(-itemTextWidth / 2f, nameY, 1.0f);
                matrices.scale(0.5f, 0.5f, 1);
                EUClient.FONT_MANAGER.drawTextWithShadow(matrices, itemText, 0, 0, vertexConsumers, Color.WHITE);
                matrices.popPose();
            }

            matrices.popPose();

            // Flush khung viền 3D & Chữ NameTag[cite: 3]
            Renderer3D.draw(Renderer3D.QUADS, Renderer3D.DEBUG_LINES, false);
            Renderer3D.QUADS.clear();
            Renderer3D.DEBUG_LINES.clear();
            vertexConsumers.endBatch();
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
        String shortName;
        if (id.startsWith("protection")) shortName = "Pro";
        else if (id.startsWith("blast_protection")) shortName = "Bla";
        else if (id.startsWith("fire_protection")) shortName = "Fir";
        else if (id.startsWith("projectile_protection")) shortName = "Proj";
        else if (id.startsWith("unbreaking")) shortName = "Unb";
        else if (id.startsWith("mending")) shortName = "Men";
        else if (id.startsWith("thorns")) shortName = "Tho";
        else if (id.startsWith("respiration")) shortName = "Res";
        else if (id.startsWith("aqua_affinity")) shortName = "Aqu";
        else if (id.startsWith("depth_strider")) shortName = "Dep";
        else if (id.startsWith("feather_falling")) shortName = "Fea";
        else if (id.startsWith("soul_speed")) shortName = "Soul";
        else if (id.startsWith("swift_sneak")) shortName = "Sne";
        else if (id.startsWith("vanishing_curse")) shortName = "Van";
        else if (id.length() > 3) shortName = id.substring(0, 3);
        else shortName = id;

        shortName = shortName.substring(0, 1).toUpperCase() + shortName.substring(1);
        return level > 1 ? shortName + " " + level : shortName;
    }

    private record ItemElement(ItemStack stack, List<String> enchantments) { }
}