package eu.client.modules.impl.core;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderOverlayEvent;
import eu.client.events.impl.TickEvent;
import eu.client.managers.HudElementRegistry;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.*;
import eu.client.utils.animations.Animation;
import eu.client.utils.animations.Easing;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer2D;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.minecraft.WorldUtils;
import eu.client.utils.text.FormattingUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import org.joml.Matrix3x2fStack;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RegisterModule(name = "HUD", description = "Renders information about the game and the client on the screen.", category = Module.Category.CORE, toggled = true, drawn = false)
public class HUDModule extends Module {
    public CategorySetting watermarkCategory = new CategorySetting("Watermark", "The settings for the client's watermark.");
    public BooleanSetting watermark = new BooleanSetting("Watermark", "Enabled", "Renders the client's name and version at the top left.", new CategorySetting.Visibility(watermarkCategory), true);
    public StringSetting watermarkText = new StringSetting("WatermarkText", "Component", "The client name that will be rendered.", new CategorySetting.Visibility(watermarkCategory), EUClient.MOD_NAME);
    public BooleanSetting watermarkVersion = new BooleanSetting("WatermarkVersion", "Version", "Renders the client's version after the name.", new CategorySetting.Visibility(watermarkCategory), true);
    public BooleanSetting watermarkMinecraftVersion = new BooleanSetting("WatermarkMinecraftVersion", "MinecraftVersion", "Renders the client's minecraft version after the version.", new CategorySetting.Visibility(watermarkCategory), false);
    public BooleanSetting watermarkRevision = new BooleanSetting("WatermarkRevision", "Revision", "Renders the client's git revision next to the version.", new BooleanSetting.Visibility(watermarkVersion, true), true);
    public BooleanSetting watermarkSync = new BooleanSetting("WatermarkSync", "ColorSync", "Uses the client's color for the version.", new CategorySetting.Visibility(watermarkCategory), false);

    public CategorySetting welcomerCategory = new CategorySetting("Welcomer", "The settings for the client's welcomer.");
    public BooleanSetting welcomer = new BooleanSetting("Welcomer", "Enabled", "Renders a nice welcome message directed to you.", new CategorySetting.Visibility(welcomerCategory), true);
    public StringSetting welcomerText = new StringSetting("WelcomerText", "Component", "The message that will be rendered.", new CategorySetting.Visibility(welcomerCategory), "Hello, [username]! :^)");
    public BooleanSetting welcomerSync = new BooleanSetting("WelcomerSync", "ColorSync", "Uses the client's color for the username.", new CategorySetting.Visibility(welcomerCategory), false);

    public CategorySetting moduleListCategory = new CategorySetting("ModuleList", "The settings for the client's list of enabled modules.");
    public BooleanSetting moduleList = new BooleanSetting("ModuleList", "Enabled", "Renders every enabled module in an organized list.", new CategorySetting.Visibility(moduleListCategory), true);
    public BooleanSetting metaData = new BooleanSetting("MetaData", "Whether or not to show module metadata in the module list.", new CategorySetting.Visibility(moduleListCategory), true);
    public ModeSetting moduleColorMode = new ModeSetting("ModuleColor", "Color", "The color mode for the modules on the list.", new CategorySetting.Visibility(moduleListCategory), "Default", new String[]{"Default", "Rainbow", "Random"});
    public ModeSetting moduleListSorting = new ModeSetting("ModuleListSorting", "Sorting", "The sorting for the modules on the list.", new CategorySetting.Visibility(moduleListCategory), "Width", new String[]{"Width", "Alphabetical"});

    public CategorySetting playerRadarCategory = new CategorySetting("Player Radar", "The settings for the client's list of players in render distance.");
    public BooleanSetting playerRadar = new BooleanSetting("PlayerRadar", "Enabled", "Renders the name of every player in render distance.", new CategorySetting.Visibility(playerRadarCategory), true);
    public NumberSetting playerRadarLimit = new NumberSetting("PlayerRadarLimit", "Limit", "The maximum amount of players that will be listed. Setting it to 0 means removing the limiter.", new CategorySetting.Visibility(playerRadarCategory), 8, 0, 100);
    public ModeSetting playerRadarSorting = new ModeSetting("PlayerRadarSorting", "Sorting", "The sorting for the players on the radar.", new CategorySetting.Visibility(playerRadarCategory), "Distance", new String[]{"None", "Distance", "Alphabetical"});
    public BooleanSetting playerRadarAntiBot = new BooleanSetting("PlayerRadarAntiBot", "AntiBot", "Prevents bots from being listed on the radar.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerIcons = new BooleanSetting("PlayerIcons", "Icons", "Renders the player's head icon next to their name.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerRadarDistance = new BooleanSetting("PlayerRadarDistance", "Distance", "Renders the distance between you and the player.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerRadarEntityID = new BooleanSetting("PlayerRadarEntityID", "EntityID", "Renders the player's entity ID.", new CategorySetting.Visibility(playerRadarCategory), false);
    public BooleanSetting playerRadarGameMode = new BooleanSetting("PlayerRadarGameMode", "GameType", "Renders the player's current gamemode.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerRadarPing = new BooleanSetting("PlayerRadarPing", "Ping", "Renders the player's current latency.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerRadarHealth = new BooleanSetting("PlayerRadarHealth", "Health", "Renders the player's current health.", new CategorySetting.Visibility(playerRadarCategory), true);
    public BooleanSetting playerRadarTotems = new BooleanSetting("PlayerRadarTotems", "Totems", "Renders the amount of totems that the player has popped.", new CategorySetting.Visibility(playerRadarCategory), true);

    public CategorySetting itemsCategory = new CategorySetting("Items", "The settings for information about items in your inventory and specific item counters.");
    public BooleanSetting armor = new BooleanSetting("Armor", "Renders the armor you're currently wearing and its status.", new CategorySetting.Visibility(itemsCategory), true);
    public ModeSetting armorDurability = new ModeSetting("ArmorDurability", "Durability", "The way that the durability will be rendered in.", new BooleanSetting.Visibility(armor, true), "Both", new String[]{"None", "Bar", "Percentage", "Both"});
    public BooleanSetting totemCounter = new BooleanSetting("TotemCounter", "Renders the amount of totems that you have in your inventory.", new CategorySetting.Visibility(itemsCategory), true);
    public BooleanSetting crystalCounter = new BooleanSetting("CrystalCounter", "Renders the amount of crystals that you have in your inventory.", new CategorySetting.Visibility(itemsCategory), true);
    public BooleanSetting xpCounter = new BooleanSetting("XPCounter", "Renders the amount of totems that you have in your inventory.", new CategorySetting.Visibility(itemsCategory), true);
    public BooleanSetting counterChatOffset = new BooleanSetting("CounterChatOffset", "ChatOffset", "Offsets the crystal and XP counter's positions whenever the chat is open.", new CategorySetting.Visibility(itemsCategory), false);

    public CategorySetting informationCategory = new CategorySetting("Information", "The settings for information about the game and the client.");
    public BooleanSetting health = new BooleanSetting("Health", "Renders your current health in the middle of the screen.", new CategorySetting.Visibility(informationCategory), false);
    public BooleanSetting ping = new BooleanSetting("Ping", "Renders your current latency to the server in milliseconds.", new CategorySetting.Visibility(informationCategory), true);
    public BooleanSetting tps = new BooleanSetting("TPS", "Renders the server's current tick-rate.", new CategorySetting.Visibility(informationCategory), true);
    public BooleanSetting fps = new BooleanSetting("FPS", "Renders the game's frames per second counter.", new CategorySetting.Visibility(informationCategory), true);
    public BooleanSetting durability = new BooleanSetting("Durability", "Renders your held item durability.", new CategorySetting.Visibility(informationCategory), true);
    public ModeSetting speed = new ModeSetting("Speed", "Renders the speed that you are currently moving at.", new CategorySetting.Visibility(informationCategory), "Kilometers", new String[]{"None", "Meters", "Kilometers"});
    public BooleanSetting uptime = new BooleanSetting("Uptime", "Renders the uptime of the client.", new CategorySetting.Visibility(informationCategory), false);
    public BooleanSetting serverBrand = new BooleanSetting("ServerBrand", "Renders the brand of the server that you are currently on.", new CategorySetting.Visibility(informationCategory), false);
    public BooleanSetting informationSync = new BooleanSetting("InformationSync", "ColorSync", "Uses the client's color for the information elements.", new CategorySetting.Visibility(informationCategory), true);
    public BooleanSetting informationChatOffset = new BooleanSetting("InformationChatOffset", "ChatOffset", "Offsets the rendering when the chat is open.", new CategorySetting.Visibility(informationCategory), true);

    public CategorySetting potionsCategory = new CategorySetting("Potions", "The settings for information about potion effects and their status.");
    public BooleanSetting potions = new BooleanSetting("Potions", "Enabled", "Renders the name and status of every potion effect you have.", new CategorySetting.Visibility(potionsCategory), true);
    public BooleanSetting potionIcons = new BooleanSetting("PotionIcons", "Icons", "Whether or not to render the icons next to the potion's name.", new CategorySetting.Visibility(potionsCategory), true);
    public ModeSetting potionColor = new ModeSetting("PotionColor", "Color", "The color that will be used in rendering the potion's text.", new CategorySetting.Visibility(potionsCategory), "Enhanced", new String[]{"Vanilla", "Enhanced", "Client"});
    public ModeSetting potionSorting = new ModeSetting("PotionSorting", "Sorting", "The sorting for the potion effects rendered.", new CategorySetting.Visibility(potionsCategory), "Alphabetical", new String[]{"None", "Width", "Alphabetical"});
    public ModeSetting vanillaPotions = new ModeSetting("VanillaPotions", "The way that the vanilla potion icons will be handled.", new CategorySetting.Visibility(potionsCategory), "Hide", new String[]{"Keep", "Move", "Hide"});

    public CategorySetting positionCategory = new CategorySetting("Position","The settings for information about your current position and velocity.");
    public BooleanSetting coordinates = new BooleanSetting("Coordinates", "Renders your current coordinates.", new CategorySetting.Visibility(positionCategory), true);
    public BooleanSetting netherCoordinates = new BooleanSetting("NetherCoordinates", "Renders your current coordinates in the alternate dimension.", new BooleanSetting.Visibility(coordinates, true), true);
    public BooleanSetting direction = new BooleanSetting("Direction", "Renders the current direction that you are facing.", new CategorySetting.Visibility(positionCategory), true);
    public BooleanSetting positionSync = new BooleanSetting("PositionSync", "ColorSync", "Uses the client's color for the position elements.", new CategorySetting.Visibility(positionCategory), true);
    public BooleanSetting positionChatOffset = new BooleanSetting("PositionChatOffset", "ChatOffset", "Offsets the text when the chat is open.", new CategorySetting.Visibility(positionCategory), true);

    public CategorySetting colorCategory = new CategorySetting("Color", "The settings for the coloring of the text.");
    public ModeSetting colorMode = new ModeSetting("Color", "The color that will be applied to the text.", new CategorySetting.Visibility(colorCategory), "Default", new String[]{"Default", "Rainbow", "Wave", "Custom"});
    public ColorSetting customColor = new ColorSetting("CustomColor", "The color that will be used for the Custom mode.", new ModeSetting.Visibility(colorMode, "Custom"), ColorUtils.getDefaultColor());
    public ModeSetting rainbowMode = new ModeSetting("Rainbow", "The mode for the HUD Rainbow.", new ModeSetting.Visibility(colorMode, "Rainbow"), "Vertical", new String[]{"Vertical", "Horizontal"});
    public NumberSetting rainbowOffset = new NumberSetting("RainbowOffset", "Offset", "The offset that will be applied to the rainbow.", new ModeSetting.Visibility(colorMode, "Rainbow", "Wave"), 10L, 1L, 50L);
    public BooleanSetting inversion = new BooleanSetting("Inversion", "Inverts primary and secondary colors.", new CategorySetting.Visibility(colorCategory), false);

    // Aggregate gates for HUDEditor -- Items/Information don't already have a single on/off (they
    // bundle several independently-toggleable pieces), so these wrap the whole block for the
    // editor's per-element enable/disable without touching the existing fine-grained settings above.
    public BooleanSetting itemsElement = new BooleanSetting("ItemsElement", "Whether the armor/totem/crystal/xp counters are shown at all. See HUDEditor.", true);
    public BooleanSetting informationElement = new BooleanSetting("InformationElement", "Whether the ping/fps/tps/etc information block is shown at all. See HUDEditor.", true);

    // Per-element drag offsets, edited via HUDEditorModule -- never shown as a normal ClickGui row.
    public PositionSetting watermarkPosition = new PositionSetting("WatermarkPosition", "Drag offset for the watermark HUD element.");
    public PositionSetting welcomerPosition = new PositionSetting("WelcomerPosition", "Drag offset for the welcomer HUD element.");
    public PositionSetting moduleListPosition = new PositionSetting("ModuleListPosition", "Drag offset for the module list HUD element.");
    public PositionSetting playerRadarPosition = new PositionSetting("PlayerRadarPosition", "Drag offset for the player radar HUD element.");
    public PositionSetting itemsPosition = new PositionSetting("ItemsPosition", "Drag offset for the item counters HUD element.");
    public PositionSetting informationPosition = new PositionSetting("InformationPosition", "Drag offset for the information HUD element.");
    public PositionSetting coordinatesPosition = new PositionSetting("CoordinatesPosition", "Drag offset for the coordinates HUD element.");

    {
        HudElementRegistry.register("Watermark", watermark, watermarkPosition);
        HudElementRegistry.register("Welcomer", welcomer, welcomerPosition);
        HudElementRegistry.register("ModuleList", moduleList, moduleListPosition);
        HudElementRegistry.register("PlayerRadar", playerRadar, playerRadarPosition);
        HudElementRegistry.register("Items", itemsElement, itemsPosition);
        HudElementRegistry.register("Information", informationElement, informationPosition);
        HudElementRegistry.register("Coordinates", coordinates, coordinatesPosition);
    }

    private final Animation potionsAnimation = new Animation(300, Easing.Method.EASE_OUT_CUBIC);
    private final Animation chatAnimation = new Animation(300, Easing.Method.EASE_OUT_CUBIC);
    private float chatOffset;

    private List<ModuleEntry> moduleEntries = new ArrayList<>();
    private List<PlayerEntry> playerEntries = new ArrayList<>();
    private List<PotionEntry> potionEntries = new ArrayList<>();

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (moduleList.getValue()) {
            Comparator<Module> widthComparator = Comparator.comparingInt(m -> -EUClient.FONT_MANAGER.getWidth(getModuleText(m)));
            Comparator<Module> alphabeticalComparator = Comparator.comparing(Module::getName);

            List<ModuleEntry> entries = new ArrayList<>();
            List<Module> modules = EUClient.MODULE_MANAGER.getModules().stream()
                    .filter(module -> module.isToggled() || module.getAnimationOffset().get(0) > 0)
                    .filter(module -> module.drawn.getValue())
                    .sorted(moduleListSorting.getValue().equalsIgnoreCase("Width") ? widthComparator : alphabeticalComparator)
                    .toList();

            for (Module module : modules) {
                String text = getModuleText(module);
                entries.add(new ModuleEntry(module, text));
            }

            moduleEntries = entries;
        }

        if (playerRadar.getValue()) {
            Comparator<AbstractClientPlayer> distanceComparator = Comparator.comparingDouble(p -> mc.player.distanceTo(p));
            Comparator<AbstractClientPlayer> alphabeticalComparator = Comparator.comparing(p -> p.getName().getString());

            List<PlayerEntry> entries = new ArrayList<>();
            List<AbstractClientPlayer> players = mc.level.players().stream()
                    .filter(p -> p != mc.player)
                    .filter(p -> !playerRadarAntiBot.getValue() || !EntityUtils.isBot(p))
                    .sorted(playerRadarSorting.getValue().equalsIgnoreCase("Distance") ? distanceComparator : alphabeticalComparator)
                    .limit(playerRadarLimit.getValue().longValue())
                    .toList();

            for (Player player : players) {
                Identifier headTexture = null;

                if (playerIcons.getValue() && mc.getConnection() != null) {
                    PlayerInfo entry = mc.getConnection().getPlayerInfo(player.getName().getString());
                    if (entry != null) {
                        headTexture = entry.getSkin().body().texturePath();
                    }
                }

                String text = player.getName().getString();

                if (playerRadarDistance.getValue()) text += ChatFormatting.WHITE + " " + new DecimalFormat("0.0").format(mc.player.distanceTo(player));
                if (playerRadarEntityID.getValue()) text += ChatFormatting.WHITE + " " + player.getId();
                if (playerRadarGameMode.getValue()) text += ChatFormatting.WHITE + " [" + EntityUtils.getGameModeName(EntityUtils.getGameMode(player)) + "]";
                if (playerRadarPing.getValue()) text += ChatFormatting.WHITE + " " + EntityUtils.getLatency(player) + "ms";
                if (playerRadarHealth.getValue()) text += ColorUtils.getHealthColor(player.getHealth() + player.getAbsorptionAmount()) + " " + new DecimalFormat("0.0").format(player.getHealth() + player.getAbsorptionAmount()) + ChatFormatting.RESET;

                int pops = EUClient.WORLD_MANAGER.getPoppedTotems().getOrDefault(player.getUUID(), 0);
                if (playerRadarTotems.getValue() && pops > 0) text += ColorUtils.getTotemColor(pops) + " -" + pops + ChatFormatting.RESET;

                entries.add(new PlayerEntry(player, text, headTexture));
            }

            playerEntries = entries;
        }

        if (potions.getValue()) {
            Comparator<MobEffectInstance> widthComparator = Comparator.comparingInt(e -> -EUClient.FONT_MANAGER.getWidth(e.getEffect().value().getDisplayName().getString() + " " + (e.getAmplifier() + 1)));
            Comparator<MobEffectInstance> alphabeticalComparator = Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString());

            List<PotionEntry> entries = new ArrayList<>();
            List<MobEffectInstance> effects = mc.player.getActiveEffects().stream()
                    .sorted(potionSorting.getValue().equalsIgnoreCase("Width") ? widthComparator : alphabeticalComparator)
                    .toList();

            for (MobEffectInstance effect : effects) {
                String text = getPotionText(effect);
                Identifier sprite = null;

                if (potionIcons.getValue()) sprite = Gui.getMobEffectSprite(effect.getEffect());

                entries.add(new PotionEntry(text, sprite, potionColor.getValue().equalsIgnoreCase("Vanilla") ? new Color(effect.getEffect().value().getColor()) : potionColor.getValue().equalsIgnoreCase("Enhanced") ? (EntityUtils.POTION_COLORS.containsKey(effect.getEffect().value()) ? EntityUtils.POTION_COLORS.get(effect.getEffect().value()) : new Color(effect.getEffect().value().getColor())) : null));
            }

            potionEntries = entries;
        }
    }

    @SubscribeEvent
    public void renderWatermark(RenderOverlayEvent event) {
        if (mc.player == null) return;

        chatOffset = chatAnimation.get(mc.screen instanceof ChatScreen ? 14 : 0);

        Renderer2D.renderQuad(event.getContext(), 2, mc.getWindow().getGuiScaledHeight() - chatOffset, mc.getWindow().getGuiScaledWidth() - 2, mc.getWindow().getGuiScaledHeight() + 12 - chatOffset, new Color(0, 0, 0, (int) (mc.options.textBackgroundOpacity().get() * 255)));

        Matrix3x2fStack matrices = event.getMatrices();

        if (watermark.getValue() || uptime.getValue()) {
            matrices.pushMatrix();
            matrices.translate(watermarkPosition.getX(), watermarkPosition.getY());

            int width = 0, lines = 0;

            if (watermark.getValue()) {
                String text = watermarkText.getValue() + (watermarkVersion.getValue() ? (watermarkSync.getValue() ? "" : inversion.getValue() ? ChatFormatting.GRAY : ChatFormatting.WHITE) + " " + EUClient.MOD_VERSION + (watermarkMinecraftVersion.getValue() ? "-mc" + EUClient.MINECRAFT_VERSION : "") + (watermarkRevision.getValue() ? "+" + EUClient.GIT_REVISION + "." + EUClient.GIT_HASH : "") : "");
                drawText(event.getContext(), text, 2, 2);
                width = Math.max(width, EUClient.FONT_MANAGER.getWidth(text));
                lines++;
            }

            if (uptime.getValue()) {
                String[] hms = FormattingUtils.formatSeconds((System.currentTimeMillis() - EUClient.UPTIME)/1000);
                String text = "Uptime " + ChatFormatting.WHITE + hms[0] + ":" + hms[1] + ":" + hms[2];
                drawText(event.getContext(), text, 2, 2 + (watermark.getValue() ? EUClient.FONT_MANAGER.getHeight() : 0), informationSync.getValue() ? null : new Color(170, 170, 170));
                width = Math.max(width, EUClient.FONT_MANAGER.getWidth(text));
                lines++;
            }

            HudElementRegistry.reportBounds("Watermark", 2, 2, 2 + width, 2 + lines * EUClient.FONT_MANAGER.getHeight());
            matrices.popMatrix();
        }

        if (welcomer.getValue()) {
            matrices.pushMatrix();
            matrices.translate(welcomerPosition.getX(), welcomerPosition.getY());

            String text = welcomerText.getValue().replace("[username]", (welcomerSync.getValue() ? "" : inversion.getValue() ? ChatFormatting.GRAY : ChatFormatting.WHITE) + mc.player.getName().getString() + ChatFormatting.RESET);
            float x = mc.getWindow().getGuiScaledWidth() / 2.0f - EUClient.FONT_MANAGER.getWidth(text) / 2.0f;
            drawText(event.getContext(), text, x, 6);

            HudElementRegistry.reportBounds("Welcomer", x, 6, x + EUClient.FONT_MANAGER.getWidth(text), 6 + EUClient.FONT_MANAGER.getHeight());
            matrices.popMatrix();
        }
    }

    @SubscribeEvent
    public void renderModuleList(RenderOverlayEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!moduleList.getValue()) return;

        float potionOffset = potionsAnimation.get(!vanillaPotions.getValue().equalsIgnoreCase("Move") || mc.player.getActiveEffects().isEmpty() ? 0 : (EntityUtils.hasNegativeEffects(mc.player) ? 51 : 25));

        Matrix3x2fStack matrices = event.getMatrices();
        matrices.pushMatrix();
        matrices.translate(moduleListPosition.getX(), moduleListPosition.getY());

        int maxWidth = 0;
        int index = 0;
        for (ModuleEntry entry : moduleEntries) {
            float x = mc.getWindow().getGuiScaledWidth() - (entry.module().getAnimationOffset().get(entry.module().isToggled() ? EUClient.FONT_MANAGER.getWidth(entry.text()) + 2 : 0));
            float y = 2 + potionOffset + (index * EUClient.FONT_MANAGER.getHeight());

            drawModuleText(entry.module(), event.getContext(), entry.text(), x, y);
            maxWidth = Math.max(maxWidth, EUClient.FONT_MANAGER.getWidth(entry.text()) + 2);
            index++;
        }

        if (!moduleEntries.isEmpty()) {
            float right = mc.getWindow().getGuiScaledWidth();
            HudElementRegistry.reportBounds("ModuleList", right - maxWidth, 2 + potionOffset, right, 2 + potionOffset + moduleEntries.size() * EUClient.FONT_MANAGER.getHeight());
        }
        matrices.popMatrix();
    }

    @SubscribeEvent
    public void renderPlayerRadar(RenderOverlayEvent event) {
        if (!playerRadar.getValue()) return;

        Matrix3x2fStack matrices = event.getMatrices();
        matrices.pushMatrix();
        matrices.translate(playerRadarPosition.getX(), playerRadarPosition.getY());

        int maxWidth = 0;
        int offset = 0;
        for (PlayerEntry entry : playerEntries) {
            if (entry.headTexture() != null) PlayerFaceExtractor.extractRenderState(event.getContext(), entry.headTexture(), 2, 1 + (EUClient.FONT_MANAGER.getHeight() * 2) + ((EUClient.FONT_MANAGER.getHeight() + 1) * offset), EUClient.FONT_MANAGER.getHeight(), true, false, Color.WHITE.getRGB());
            int textX = 2 + (entry.headTexture() != null ? EUClient.FONT_MANAGER.getHeight() + 2 : 0);
            drawText(event.getContext(), entry.text(), textX, 2 + (EUClient.FONT_MANAGER.getHeight() * 2) + ((EUClient.FONT_MANAGER.getHeight() + 1) * offset), EUClient.FRIEND_MANAGER.contains(entry.player().getName().getString()) ? EUClient.FRIEND_MANAGER.getDefaultFriendColor() : null);

            maxWidth = Math.max(maxWidth, textX + EUClient.FONT_MANAGER.getWidth(entry.text()));
            offset++;
        }

        if (!playerEntries.isEmpty()) {
            HudElementRegistry.reportBounds("PlayerRadar", 2, 1 + EUClient.FONT_MANAGER.getHeight() * 2, maxWidth, 1 + (EUClient.FONT_MANAGER.getHeight() * 2) + ((EUClient.FONT_MANAGER.getHeight() + 1) * playerEntries.size()));
        }
        matrices.popMatrix();
    }

    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_SLOTS = {
            net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.HEAD
    };

    private static int countItem(Player player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    @SubscribeEvent
    public void renderItems(RenderOverlayEvent event) {
        if (mc.player == null) return;
        if (!itemsElement.getValue()) return;

        Matrix3x2fStack matrices = event.getMatrices();
        matrices.pushMatrix();
        matrices.translate(itemsPosition.getX(), itemsPosition.getY());

        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int bottomY = mc.getWindow().getGuiScaledHeight();
        HudElementRegistry.reportBounds("Items", centerX - 90, bottomY - 60, centerX + 124, bottomY - 15);

        if (armor.getValue()) {
            int offset = 0;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                ItemStack stack = mc.player.getItemBySlot(slot);
                if (stack.isEmpty()) continue;

                int wateroffset = (mc.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || mc.player.getAirSupply() < mc.player.getMaxAirSupply()) ? 10 : 0;

                int x = mc.getWindow().getGuiScaledWidth() / 2 + 69 - (18 * offset);
                int y = mc.getWindow().getGuiScaledHeight() - 55 - wateroffset;

                event.getContext().item(stack, x, y);
                // itemDecorations() draws vanilla's own durability bar (plus stack count/cooldown,
                // irrelevant for armor) -- it used to run unconditionally regardless of this mode,
                // so "Percentage" always showed the bar too instead of just the percentage text.
                if (armorDurability.getValue().equalsIgnoreCase("Bar") || armorDurability.getValue().equalsIgnoreCase("Both")) {
                    event.getContext().itemDecorations(mc.font, stack, x, y);
                }

                int damage = stack.getDamageValue();
                int maxDamage = stack.getMaxDamage();

                // PORT: && binds tighter than || -- this used to parse as
                // `Percentage || (Both && maxDamage > 0)`, so plain "Percentage" mode skipped the
                // maxDamage>0 guard entirely and divided by zero on any unbreakable armor piece.
                if ((armorDurability.getValue().equalsIgnoreCase("Percentage") || armorDurability.getValue().equalsIgnoreCase("Both")) && maxDamage > 0) {
                    matrices.pushMatrix();
                    matrices.scale(0.625f, 0.625f);
                    drawText(event.getContext(), (((maxDamage - damage) * 100) / maxDamage) + "%", (int) (((mc.getWindow().getGuiScaledWidth() >> 1) + 70 - (18 * offset)) * 1.6F), (int) ((mc.getWindow().getGuiScaledHeight() - 58 - wateroffset) * 1.6F - 5), false, new Color(1.0f - ((maxDamage - damage) / (float) maxDamage), (maxDamage - damage) / (float) maxDamage, 0));
                    matrices.popMatrix();
                }

                offset++;
            }
        }

        if (totemCounter.getValue()) {
            int totems = countItem(mc.player, Items.TOTEM_OF_UNDYING);
            if (totems > 0) {
                ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING);
                int x = (mc.getWindow().getGuiScaledWidth() / 2) - 9;
                int y = mc.getWindow().getGuiScaledHeight() - 55 - ((mc.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && mc.gameMode.getPlayerMode() != GameType.CREATIVE) ? 10 : 0);

                event.getContext().item(stack, x, y);
                event.getContext().itemDecorations(mc.font, stack, x, y, String.valueOf(totems));
            }
        }

        boolean renderedXpCounter = false;
        if (xpCounter.getValue()) {
            int experienceBottles = countItem(mc.player, Items.EXPERIENCE_BOTTLE);
            if (experienceBottles > 0) {
                ItemStack stack = new ItemStack(Items.EXPERIENCE_BOTTLE);
                float x = (mc.getWindow().getGuiScaledWidth() / 2) + 106;
                float y = mc.getWindow().getGuiScaledHeight() - 20 - (counterChatOffset.getValue() ? chatOffset : 0);

                matrices.pushMatrix();
                matrices.translate(x, y);
                event.getContext().item(stack, 0, 0);
                event.getContext().itemDecorations(mc.font, stack, 0, 0, String.valueOf(experienceBottles));
                matrices.popMatrix();

                renderedXpCounter = true;
            }
        }

        if (crystalCounter.getValue()) {
            int crystals = countItem(mc.player, Items.END_CRYSTAL);
            if (crystals > 0) {
                ItemStack stack = new ItemStack(Items.END_CRYSTAL);
                float x = (mc.getWindow().getGuiScaledWidth() / 2) + 106;
                float y = mc.getWindow().getGuiScaledHeight() - (renderedXpCounter ? 40 : 20) - (counterChatOffset.getValue() ? chatOffset : 0);

                matrices.pushMatrix();
                matrices.translate(x, y);
                event.getContext().item(stack, 0, 0);
                event.getContext().itemDecorations(mc.font, stack, 0, 0, String.valueOf(crystals));
                matrices.popMatrix();
            }
        }

        matrices.popMatrix();
    }

    @SubscribeEvent
    public void renderInformation(RenderOverlayEvent event) {
        if (mc.player == null) return;
        if (!informationElement.getValue()) return;

        Matrix3x2fStack matrices = event.getMatrices();
        matrices.pushMatrix();
        matrices.translate(informationPosition.getX(), informationPosition.getY());

        int offset = 0;

        float chatOffset = informationChatOffset.getValue() ? this.chatOffset : 0;

        if (health.getValue()) {
            // Was embedding the ChatFormatting as a raw "§x" text prefix and always drawing with a
            // hardcoded Color.WHITE -- drawTextWithOutline's custom-font path only strips control
            // codes for its 4 outline copies (FontManager.drawTextWithOutline), not the main glyph
            // draw, so the literal '§'+code characters got laid out as real (bogus/undefined) glyphs
            // in front of the digits instead of being parsed into a color, rendering as a solid
            // clump instead of "20". Compute the real Color directly and pass a plain digit string.
            String text = new DecimalFormat("0").format(mc.player.getHealth() + mc.player.getAbsorptionAmount());
            Color healthColor = new Color(ColorUtils.getHealthColor(mc.player.getHealth() + mc.player.getAbsorptionAmount()).getColor());
            EUClient.FONT_MANAGER.drawTextWithOutline(event.getContext(), text, mc.getWindow().getGuiScaledWidth() / 2 - EUClient.FONT_MANAGER.getWidth(text) / 2, mc.getWindow().getGuiScaledHeight() / 2 + 16, healthColor, Color.BLACK);
        }

        if (potions.getValue()) {
            for (PotionEntry entry : potionEntries) {
                if (entry.sprite() != null) {
                    matrices.pushMatrix();
                    matrices.translate(mc.getWindow().getGuiScaledWidth() - 2 - EUClient.FONT_MANAGER.getWidth(entry.text()) - EUClient.FONT_MANAGER.getHeight() - 2, mc.getWindow().getGuiScaledHeight() - chatOffset - 2 - EUClient.FONT_MANAGER.getHeight() - (EUClient.FONT_MANAGER.getHeight() * offset) - 1);
                    event.getContext().blitSprite(RenderPipelines.GUI_TEXTURED, entry.sprite(), 0, 0, EUClient.FONT_MANAGER.getHeight(), EUClient.FONT_MANAGER.getHeight());
                    matrices.popMatrix();
                }

                drawText(event.getContext(), entry.text(), mc.getWindow().getGuiScaledWidth() - 2 - EUClient.FONT_MANAGER.getWidth(entry.text()), mc.getWindow().getGuiScaledHeight() - chatOffset - 2 - EUClient.FONT_MANAGER.getHeight() - (EUClient.FONT_MANAGER.getHeight() * offset), potionColor.getValue().equals("Client") && colorMode.getValue().equals("Rainbow") && rainbowMode.getValue().equals("Horizontal"), entry.color());
                offset++;
            }
        }

        List<String> informationEntries = new ArrayList<>();

        if (ping.getValue()) informationEntries.add(getPrimary() + "Ping " + getSecondary() + EUClient.SERVER_MANAGER.getPing() + "ms");
        if (fps.getValue()) informationEntries.add(getPrimary() + "FPS " + getSecondary() + EUClient.RENDER_MANAGER.getFps());
        if (durability.getValue()) informationEntries.add("Durability " + (mc.player.getMainHandItem().getMaxDamage() - mc.player.getMainHandItem().getDamageValue()));
        if (!speed.getValue().equalsIgnoreCase("None")) informationEntries.add(getPrimary() + "Speed " + getSecondary() + new DecimalFormat("0.00").format(EntityUtils.getSpeed(mc.player, speed.getValue().equalsIgnoreCase("Meters") ? EntityUtils.SpeedUnit.METERS : EntityUtils.SpeedUnit.KILOMETERS)) + (speed.getValue().equalsIgnoreCase("Meters") ? "m/s" : "km/h"));
        if (serverBrand.getValue()) informationEntries.add(getPrimary() + "Brand " + getSecondary() + EUClient.SERVER_MANAGER.getServerBrand());

        float tickRate = EUClient.SERVER_MANAGER.getTickRate();
        if (tps.getValue()) informationEntries.add(getPrimary() + "TPS " + getSecondary() + (tickRate > 19.79 ? "20.00" : new DecimalFormat("00.00").format(tickRate)));

        if (!informationEntries.isEmpty()) {
            informationEntries.sort(Comparator.comparingInt(EUClient.FONT_MANAGER::getWidth).reversed());
            for (String text : informationEntries) {
                if (text.startsWith("Durability")) {
                    if (mc.player.getMainHandItem().isDamageableItem()) {
                        int maxDamage = mc.player.getMainHandItem().getMaxDamage(), damage = mc.player.getMainHandItem().getDamageValue();
                        String s = String.valueOf(maxDamage - damage);

                        drawText(event.getContext(), getPrimary() + "Durability ", mc.getWindow().getGuiScaledWidth() - 2 - EUClient.FONT_MANAGER.getWidth("Durability ") - EUClient.FONT_MANAGER.getWidth(s), mc.getWindow().getGuiScaledHeight() - chatOffset - 2 - EUClient.FONT_MANAGER.getHeight() + (offset * -EUClient.FONT_MANAGER.getHeight()), informationSync.getValue() ? null : new Color(170, 170, 170));
                        drawText(event.getContext(), s, mc.getWindow().getGuiScaledWidth() - 2 - EUClient.FONT_MANAGER.getWidth(s), mc.getWindow().getGuiScaledHeight() - chatOffset - 2 - EUClient.FONT_MANAGER.getHeight() + (offset * -EUClient.FONT_MANAGER.getHeight()), false, new Color(1.0f - ((maxDamage - damage) / (float) maxDamage), (maxDamage - damage) / (float) maxDamage, 0));
                        offset++;
                    }
                } else {
                    drawText(event.getContext(), text, mc.getWindow().getGuiScaledWidth() - 2 - EUClient.FONT_MANAGER.getWidth(text), mc.getWindow().getGuiScaledHeight() - chatOffset - 2 - EUClient.FONT_MANAGER.getHeight() + (offset * -EUClient.FONT_MANAGER.getHeight()), informationSync.getValue() ? null : new Color(170, 170, 170));
                    offset++;
                }
            }

            int maxWidth = 0;
            for (String text : informationEntries) maxWidth = Math.max(maxWidth, EUClient.FONT_MANAGER.getWidth(text));
            float right = mc.getWindow().getGuiScaledWidth();
            float bottom = mc.getWindow().getGuiScaledHeight() - chatOffset;
            HudElementRegistry.reportBounds("Information", right - 2 - maxWidth, bottom - 2 - offset * EUClient.FONT_MANAGER.getHeight(), right, bottom);
        }

        matrices.popMatrix();
    }

    @SubscribeEvent
    public void renderCoordinates(RenderOverlayEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!coordinates.getValue() && !direction.getValue()) return;

        Matrix3x2fStack matrices = event.getMatrices();
        matrices.pushMatrix();
        matrices.translate(coordinatesPosition.getX(), coordinatesPosition.getY());

        float chatOffset = positionChatOffset.getValue() ? this.chatOffset : 0;
        int offset = 0;
        int maxWidth = 0;
        int lines = 0;

        if (coordinates.getValue())  {
            String text = getSecondary() + String.valueOf(mc.player.getBlockX()) + (netherCoordinates.getValue() ? ChatFormatting.GRAY + " [" + getSecondary() + WorldUtils.getNetherPosition(mc.player.getBlockX()) + ChatFormatting.GRAY + "]" : "") + (inversion.getValue() || positionSync.getValue() ? ChatFormatting.RESET : ChatFormatting.GRAY) + ", " + getSecondary() + mc.player.getBlockY() + (inversion.getValue() || positionSync.getValue() ? ChatFormatting.RESET : ChatFormatting.GRAY) + ", " + getSecondary() + mc.player.getBlockZ() + (netherCoordinates.getValue() ? ChatFormatting.GRAY + " [" + getSecondary() + WorldUtils.getNetherPosition(mc.player.getBlockZ()) + ChatFormatting.GRAY + "]" : "");

            drawText(event.getContext(), text, 2, mc.getWindow().getGuiScaledHeight() - chatOffset - offset - EUClient.FONT_MANAGER.getHeight() - 2);
            maxWidth = Math.max(maxWidth, EUClient.FONT_MANAGER.getWidth(text));
            offset += EUClient.FONT_MANAGER.getHeight();
            lines++;
        }

        if (direction.getValue()) {
            String text = getPrimary() + WorldUtils.getFacingName(mc.player.getYRot()) + (inversion.getValue() ? getSecondary() : ChatFormatting.GRAY) + " [" + (inversion.getValue() ? getSecondary() : ChatFormatting.WHITE) + WorldUtils.getFacingAxes(mc.player.getYRot()) + (inversion.getValue() ? getSecondary() : ChatFormatting.GRAY) + "]";
            drawText(event.getContext(), text, 2, mc.getWindow().getGuiScaledHeight() - chatOffset - offset - EUClient.FONT_MANAGER.getHeight() - 2, positionSync.getValue() ? null : Color.WHITE);
            maxWidth = Math.max(maxWidth, EUClient.FONT_MANAGER.getWidth(text));
            lines++;
        }

        float bottom = mc.getWindow().getGuiScaledHeight() - chatOffset;
        HudElementRegistry.reportBounds("Coordinates", 2, bottom - 2 - lines * EUClient.FONT_MANAGER.getHeight(), 2 + maxWidth, bottom - 2);

        matrices.popMatrix();
    }

    private void drawModuleText(Module module, GuiGraphicsExtractor context, String text, float x, float y) {
        Color color = getHudColor(y);
        if (moduleColorMode.getValue().equalsIgnoreCase("Rainbow")) {
            long index = ((long) y / EUClient.FONT_MANAGER.getHeight()) * (rainbowOffset.getValue().longValue() * 10L);
            color = ColorUtils.getOffsetRainbow(index);
        } else if (moduleColorMode.getValue().equals("Random")) {
            color = ColorUtils.getHashColor(module.getName());
        }

        drawText(context, text, x, y, (moduleColorMode.getValue().equals("Rainbow") || (moduleColorMode.getValue().equals("Default") && colorMode.getValue().equals("Rainbow")))&& rainbowMode.getValue().equals("Horizontal"), color);
    }

    private void drawText(GuiGraphicsExtractor context, String text, float x, float y) {
        drawText(context, text, x, y, null);
    }

    public void drawText(GuiGraphicsExtractor context, String text, float x, float y, Color color) {
        drawText(context, text, x, y, colorMode.getValue().equals("Rainbow") && rainbowMode.getValue().equals("Horizontal"), color);
    }

    public void drawText(GuiGraphicsExtractor context, String text, float x, float y, boolean rainbow, Color color) {
        if (color == null) color = getHudColor(y);

        Matrix3x2fStack matrices = context.pose();

        matrices.pushMatrix();
        matrices.translate(x, y);

        if (rainbow)  {
            EUClient.FONT_MANAGER.drawRainbowString(context, text, 0, 0, rainbowOffset.getValue().longValue() * 5L);
        } else {
            EUClient.FONT_MANAGER.drawTextWithShadow(context, text, 0, 0, color);
        }

        matrices.popMatrix();
    }

    private Color getHudColor(float offset) {
        if (colorMode.getValue().equalsIgnoreCase("Rainbow")) {
            long index = ((long) offset / EUClient.FONT_MANAGER.getHeight()) * (rainbowOffset.getValue().longValue() * 10L);
            return ColorUtils.getOffsetRainbow(index);
        } else if (colorMode.getValue().equalsIgnoreCase("Wave")) {
            long index = ((long) offset / EUClient.FONT_MANAGER.getHeight()) * (rainbowOffset.getValue().longValue() * 20L);
            return ColorUtils.getOffsetWave(ColorUtils.getGlobalColor(), index);
        } else if (colorMode.getValue().equalsIgnoreCase("Custom")) {
            return ColorUtils.getColor(customColor.getColor(), 255);
        }
        return ColorUtils.getGlobalColor();
    }

    private String getModuleText(Module module) {
        return module.getName() + (module.getMetaData().isEmpty() || !metaData.getValue() ? "" : ChatFormatting.GRAY + " [" + ChatFormatting.WHITE + module.getMetaData() + ChatFormatting.GRAY + "]");
    }

    private String getPotionText(MobEffectInstance effect) {
        String duration;
        if (effect.isInfiniteDuration()) {
            duration = "**:**";
        } else {
            int seconds = Math.round(effect.getDuration() / mc.level.tickRateManager().tickrate());
            duration = String.format("%02d:%02d", seconds / 60, seconds % 60);
        }
        return effect.getEffect().value().getDisplayName().getString() + " " + (effect.getAmplifier() + 1) + " " + ChatFormatting.WHITE + duration;
    }

    private ChatFormatting getPrimary() {
        return inversion.getValue() ? ChatFormatting.GRAY : ChatFormatting.RESET;
    }

    private ChatFormatting getSecondary() {
        return inversion.getValue() ? ChatFormatting.RESET : ChatFormatting.WHITE;
    }

    public record ModuleEntry(Module module, String text) {}
    public record PlayerEntry(Player player, String text, Identifier headTexture) {}
    public record PotionEntry(String text, Identifier sprite, Color color) {}
}
