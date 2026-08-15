package eu.client.modules.impl.combat;

import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.KeyInputEvent;
import eu.client.events.impl.PlayerUpdateEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.BooleanSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.settings.impl.NumberSetting;
import eu.client.utils.minecraft.EntityUtils;
import eu.client.utils.minecraft.InventoryUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;

@RegisterModule(name = "AutoArmor", description = "Automatically equips the best armor.", category = Module.Category.COMBAT, proxyEnhanced = true)
public class AutoArmorModule extends Module {
    public ModeSetting health = new ModeSetting("Health", "The health priority to apply.", "Highest", new String[]{"Highest", "Lowest", "Any"});
    public BooleanSetting elytraPriority = new BooleanSetting("ElytraPriority", "Prioritizes elytra over armor pieces.", true);
    public BooleanSetting preserve = new BooleanSetting("Preserve", "Preserve low health armor to avoid it from breaking.", false);
    public NumberSetting preserveHealth = new NumberSetting("PreserveHealth", "The minimum health of armor to preserve it.", 20.0f, 10.0f, 50.0f);
    public BooleanSetting elytra = new BooleanSetting("Elytra", "Equips an elytra instead of a chestplate.", false);
    // Set to toggle Elytra without opening the ClickGui -- same Bind mechanism modules use
    // (BindSetting + KeyInputEvent/MouseInputEvent), just wired to this one setting instead of
    // the whole module's toggle.
    public eu.client.settings.impl.BindSetting elytraBind = new eu.client.settings.impl.BindSetting("ElytraBind", "Keybind that toggles Elytra on/off.", 0).disableHoldModes();
    public BooleanSetting smartElytra = new BooleanSetting("SmartElytra", "Chooses when to enable elytra in a more convenient way.", false);

    // Per-piece protection preference: "Prot" ranks by the Protection enchant level, "Blast"
    // ranks by Blast Protection specifically -- getProtectionAmount() weighs ALL protection-type
    // enchants together into one blended score, which doesn't let you e.g. prefer a Blast Prot
    // chestplate over a higher combined-score Protection one for crystal PvP specifically.
    public ModeSetting headMode = new ModeSetting("HeadMode", "Which protection enchant Head armor is ranked by.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting chestMode = new ModeSetting("ChestMode", "Which protection enchant Chest armor is ranked by.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting legsMode = new ModeSetting("LegsMode", "Which protection enchant Legs armor is ranked by.", "Prot", new String[]{"Blast", "Prot"});
    public ModeSetting feetMode = new ModeSetting("FeetMode", "Which protection enchant Feet armor is ranked by.", "Prot", new String[]{"Blast", "Prot"});

    private int ticks = 0;

    @SubscribeEvent
    public void onElytraBind(KeyInputEvent event) {
        if (shouldRunOnProxy() || getNull()) return;
        if (elytraBind.getValue() != 0 && event.getKey() == elytraBind.getValue() && elytraBind.getMode().equals("Bind")) elytra.setValue(!elytra.getValue());
    }

    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        if (shouldRunOnProxy()) return;
        if(getNull() || !smartElytra.getValue()) return;

        if(event.getKey() == 32 && !elytra.getValue() && !mc.player.onGround() && !EntityUtils.isInWeb(mc.player)) {
            elytra.setValue(true);
        }
    }

    @SubscribeEvent
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (shouldRunOnProxy()) return;
        if (mc.player == null || mc.level == null) return;

        if(smartElytra.getValue() && elytra.getValue()) {
            if((mc.player.onGround() && !(mc.player.getMainHandItem().getItem() instanceof ExperienceBottleItem) || EntityUtils.isInWeb(mc.player))) elytra.setValue(false);
        }

        if (ticks <= 0) {
            if (InventoryUtils.inInventoryScreen()) return;

            update(EquipmentSlot.HEAD, 5);
            // Was "!(elytraPriority && slot38==ELYTRA) || elytra.getValue()" -- with elytraPriority
            // on and an elytra already worn (equipped by a PREVIOUS press), turning elytra OFF made
            // this false || false = skip update() entirely, so AutoArmor never even looked at
            // swapping back to a chestplate (reported: pressing the elytra bind a second time did
            // nothing). The only case that should actually SKIP the update is "already exactly what
            // we want" (elytra wanted, priority on, already worn) -- an optimization to not re-run
            // the compare/swap loop on an already-correct elytra, not a blanket "elytra worn -> hands
            // off the chest slot" rule. Every other case (including elytra OFF) has to run update()
            // so its own elytra.getValue() branch can pick the chestplate path instead.
            if (!(elytra.getValue() && elytraPriority.getValue() && mc.player.getInventory().getItem(38).getItem() == Items.ELYTRA)) update(EquipmentSlot.CHEST, 6);
            update(EquipmentSlot.LEGS, 7);
            update(EquipmentSlot.FEET, 8);
        }

        ticks--;
    }

    private void update(EquipmentSlot type, int x) {
        int elytraSlot = findElytra();
        boolean flag = elytra.getValue() && type == EquipmentSlot.CHEST;

        int slot = type == EquipmentSlot.HEAD ? 39 : type == EquipmentSlot.CHEST ? 38 : type == EquipmentSlot.LEGS ? 37 : 36;
        int armor = flag ? elytraSlot : findArmor(type);
        int best = flag ? compareElytra(38, armor) : compare(slot, armor, true, type);

        if (armor != -1 && best != slot) {
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, x, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, InventoryUtils.indexToSlot(armor), 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, x, 0, ContainerInput.PICKUP, mc.player);

            ticks = 2 + EUClient.SERVER_MANAGER.getPingDelay();
        }
    }

    private int compare(int x, int y, boolean swap, EquipmentSlot type) {
        if (y == -1) return x;
        if (!isArmor(mc.player.getInventory().getItem(x))) return y;

        String enchantId = switch (type) {
            case HEAD -> headMode.getValue();
            case CHEST -> chestMode.getValue();
            case LEGS -> legsMode.getValue();
            default -> feetMode.getValue();
        };
        enchantId = enchantId.equalsIgnoreCase("Blast") ? "blast_protection" : "protection";

        if (getEnchantLevel(mc.player.getInventory().getItem(x), enchantId) < getEnchantLevel(mc.player.getInventory().getItem(y), enchantId))
            return y;

        if (preserve.getValue() && getDurability(x) < preserveHealth.getValue().floatValue())
            return getDurability(x) < getDurability(y) ? y : x;

        if (!swap) {
            if (health.getValue().equals("Highest") && getDurability(x) < getDurability(y)) return y;
            else if (health.getValue().equals("Lowest") && getDurability(x) > getDurability(y)) return y;
        }

        return x;
    }

    private int compareElytra(int x, int y) {
        if(y == -1) return x;
        if (mc.player.getInventory().getItem(x).getItem() != Items.ELYTRA) return y;

        if (health.getValue().equals("Highest") && getDurability(x) < getDurability(y)) return y;
        else if (health.getValue().equals("Lowest") && getDurability(x) > getDurability(y)) return y;

        return x;
    }

    private int findArmor(EquipmentSlot type) {
        int slot = -1;
        for (int i = InventoryUtils.HOTBAR_START; i <= InventoryUtils.INVENTORY_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isArmor(stack)) continue;

            if (getSlotType(stack).equals(type)) {
                slot = compare(i, slot, false, type);
            }
        }

        return slot;
    }

    private int findElytra() {
        int slot = -1;
        for (int i = InventoryUtils.HOTBAR_START; i <= InventoryUtils.INVENTORY_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);

            if(stack.getItem() != Items.ELYTRA) continue;;

            slot = compareElytra(i, slot);
        }

        return slot;
    }

    private int getEnchantLevel(ItemStack stack, String enchantId) {
        for (var enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.getRegisteredName().replace("minecraft:", "").equals(enchantId))
                return stack.getEnchantments().getLevel(enchantment);
        }
        return 0;
    }

    private float getDurability(int slot) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        return ((stack.getMaxDamage() - stack.getDamageValue()) * 100.0f) / stack.getMaxDamage();
    }

    private EquipmentSlot getSlotType(ItemStack itemStack) {
        if (itemStack.has(DataComponents.GLIDER)) return EquipmentSlot.CHEST;
        return itemStack.get(DataComponents.EQUIPPABLE).slot();
    }

    private boolean isArmor(ItemStack itemStack) {
        // Real root cause of "won't swap elytra->chestplate": EquipmentSlot.Type is a property of
        // the SLOT (CHEST is always HUMANOID_ARMOR), not the item, so a worn Elytra read as real
        // armor here. compare(currentlyWorn=elytra, candidate=chestplate) then tied 0 enchant
        // levels vs 0 and fell through to "return x" (keep current), never swapping. Elytra isn't
        // a HUMANOID_ARMOR-type item for our purposes here, regardless of which slot it occupies.
        if (itemStack.getItem() == Items.ELYTRA) return false;

        // Same class of bug, different item: mob heads (creeper/skeleton/zombie/piglin/wither
        // skull/player head) are EQUIPPABLE into HEAD, and HEAD's EquipmentSlot.Type is also
        // HUMANOID_ARMOR -- that's a property of the SLOT, not the item, same as above. Heads
        // carry no armor value at all (they're plain SkullBlockItems -- this componentized item
        // system has no dedicated ArmorItem class anymore to instanceof-check against), so
        // findArmor() picked one as the "best" helmet whenever the inventory had no real helmet,
        // equipping a decorative head instead of leaving the head slot alone. Real armor grants a
        // generic.armor attribute modifier; heads grant none -- check that directly instead.
        boolean grantsArmor = itemStack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
                .anyMatch(entry -> entry.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR));
        if (!grantsArmor) return false;

        var equippable = itemStack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }
}
