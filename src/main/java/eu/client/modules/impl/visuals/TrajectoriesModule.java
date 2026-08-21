package eu.client.modules.impl.visuals;

import eu.client.events.SubscribeEvent;
import eu.client.events.impl.RenderWorldEvent;
import eu.client.modules.Module;
import eu.client.modules.RegisterModule;
import eu.client.settings.impl.CategorySetting;
import eu.client.settings.impl.ColorSetting;
import eu.client.settings.impl.ModeSetting;
import eu.client.utils.color.ColorUtils;
import eu.client.utils.graphics.Renderer3D;
import eu.client.utils.minecraft.EntityUtils;
import net.minecraft.world.level.block.Blocks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

@RegisterModule(name = "Trajectories", description = "Draws a predicted trajectory of where throwables will end up when you throw them.", category = Module.Category.VISUALS)
public class TrajectoriesModule extends Module {
    public ColorSetting lineColor = new ColorSetting("LineColor", "The color of the trajectory line.", ColorUtils.getDefaultOutlineColor());

    public CategorySetting entitiesCategory = new CategorySetting("Entities", "The rendering that will be applied to entities hit by the trajectory.");
    public ModeSetting entitiesMode = new ModeSetting("EntitiesMode", "Mode", "The rendering that will be applied to the target entity.", new CategorySetting.Visibility(entitiesCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting entitiesFillColor = new ColorSetting("EntitiesFillColor", "FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(entitiesMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting entitiesOutlineColor = new ColorSetting("EntitiesOutlineColor", "OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(entitiesMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    public CategorySetting blocksCategory = new CategorySetting("Blocks", "The rendering that will be applied to blocks hit by the trajectory.");
    public ModeSetting blocksMode = new ModeSetting("BlocksMode", "Mode", "The rendering that will be applied to the target block.", new CategorySetting.Visibility(blocksCategory), "Both", new String[]{"None", "Fill", "Outline", "Both"});
    public ColorSetting blocksFillColor = new ColorSetting("BlocksFillColor", "FillColor", "The color that will be used for the fill rendering.", new ModeSetting.Visibility(blocksMode, "Fill", "Both"), ColorUtils.getDefaultFillColor());
    public ColorSetting blocksOutlineColor = new ColorSetting("BlocksOutlineColor", "OutlineColor", "The color that will be used for the outline rendering.", new ModeSetting.Visibility(blocksMode, "Outline", "Both"), ColorUtils.getDefaultOutlineColor());

    private List<Entity> hitEntities = new ArrayList<>();

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (mc.player == null || mc.level == null) return;
        // PORT (26.2): Options.hideGui removed -- moved to Gui's own hud field, see InGameHudMixin's
        // PORT comment for the real source trail.
        if (mc.gui.hud.isHidden() || !mc.options.getCameraType().isFirstPerson()) return;

        InteractionHand activeHand;

        if (mc.player.getMainHandItem().getItem() instanceof BowItem || mc.player.getMainHandItem().getItem() instanceof CrossbowItem || EntityUtils.isThrowable(mc.player.getMainHandItem().getItem())) activeHand = InteractionHand.MAIN_HAND;
        else if (mc.player.getOffhandItem().getItem() instanceof BowItem || mc.player.getOffhandItem().getItem() instanceof CrossbowItem || EntityUtils.isThrowable(mc.player.getOffhandItem().getItem())) activeHand = InteractionHand.OFF_HAND;
        else return;

        boolean prevBobView = mc.options.bobView().get();
        mc.options.bobView().set(false);

        hitEntities.clear();

        // getYRot()/getXRot() are the RAW current-tick values, only ever updated once per tick --
        // the start position two lines below already lerps (xo/yo/zo -> current) so it moves
        // smoothly every render frame, but the aim direction built from raw yaw/pitch snapped
        // instantly to its new value the instant a tick landed, then held flat until the next
        // tick -- a visible jitter/kink at the trajectory's own origin every tick boundary, even
        // though the base position right next to it was moving continuously. Lerp yaw the same
        // way (yRotO -> getYRot()); project() does the same for pitch internally.
        float yaw = Mth.lerp(event.getTickDelta(), mc.player.yRotO, mc.player.getYRot());

        if ((mc.player.getOffhandItem().getItem() instanceof CrossbowItem && EnchantmentHelper.getItemEnchantmentLevel(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MULTISHOT), mc.player.getOffhandItem()) != 0) || (mc.player.getMainHandItem().getItem() instanceof CrossbowItem && EnchantmentHelper.getItemEnchantmentLevel(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MULTISHOT), mc.player.getMainHandItem()) != 0)) {
            project(event.getMatrices(), activeHand == InteractionHand.OFF_HAND ? mc.player.getOffhandItem().getItem() : mc.player.getMainHandItem().getItem(), yaw - 10, event.getTickDelta());
            project(event.getMatrices(), activeHand == InteractionHand.OFF_HAND ? mc.player.getOffhandItem().getItem() : mc.player.getMainHandItem().getItem(), yaw, event.getTickDelta());
            project(event.getMatrices(), activeHand == InteractionHand.OFF_HAND ? mc.player.getOffhandItem().getItem() : mc.player.getMainHandItem().getItem(), yaw + 10, event.getTickDelta());
        } else {
            project(event.getMatrices(), activeHand == InteractionHand.OFF_HAND ? mc.player.getOffhandItem().getItem() : mc.player.getMainHandItem().getItem(), yaw, event.getTickDelta());
        }

        mc.options.bobView().set(prevBobView);
    }

    private void project(PoseStack matrices, Item item, float yaw, float tickDelta) {
        double x = Mth.lerp(tickDelta, mc.player.xo, mc.player.getX());
        double y = Mth.lerp(tickDelta, mc.player.yo, mc.player.getY());
        double z = Mth.lerp(tickDelta, mc.player.zo, mc.player.getZ());

        y = y + mc.player.getEyeHeight(mc.player.getPose()) - 0.1000000014901161;

        if (item == mc.player.getMainHandItem().getItem()) {
            x = x - Mth.cos(yaw / 180.0f * 3.1415927f) * 0.16f;
            z = z - Mth.sin(yaw / 180.0f * 3.1415927f) * 0.16f;
        } else {
            x = x + Mth.cos(yaw / 180.0f * 3.1415927f) * 0.16f;
            z = z + Mth.sin(yaw / 180.0f * 3.1415927f) * 0.16f;
        }

        float maxDistance = item instanceof BowItem ? 1.0f : 0.4f;

        // Same lerp as yaw above (see onRenderWorld) -- pitch is the other half of "which way is
        // this pointing", raw getXRot() jitters at tick boundaries the same way raw getYRot() did.
        float pitch = Mth.lerp(tickDelta, mc.player.xRotO, mc.player.getXRot());

        double motionX = -Mth.sin(yaw / 180.0f * 3.1415927f) * Mth.cos(pitch / 180.0f * 3.1415927f) * maxDistance;
        double motionY = -Mth.sin((pitch - getThrowPitch(item)) / 180.0f * 3.141593f) * maxDistance;
        double motionZ = Mth.cos(yaw / 180.0f * 3.1415927f) * Mth.cos(pitch / 180.0f * 3.1415927f) * maxDistance;

        float power = mc.player.getTicksUsingItem() / 20.0f;
        power = (power * power + power * 2.0f) / 3.0f;
        if (power > 1.0f || power == 0) power = 1.0f;

        float distance = Mth.sqrt((float) (motionX * motionX + motionY * motionY + motionZ * motionZ));
        motionX /= distance;
        motionY /= distance;
        motionZ /= distance;

        float pow = (item instanceof BowItem ? (power * 2.0f) : item instanceof CrossbowItem ? (2.2f) : 1.0f) * getThrowVelocity(item);
        motionX *= pow;
        motionY *= pow;
        motionZ *= pow;

        if (!mc.player.onGround()) motionY += mc.player.getDeltaMovement().y;

        Matrix4f matrix4f = matrices.last().pose();
        Vec3 lastPosition;
        boolean landed = false;
        HitResult result = null;
        Entity entity = null;

        while(!landed && y > -65) {
            lastPosition = new Vec3(x, y, z);
            x += motionX;
            y += motionY;
            z += motionZ;

            if (mc.level.getBlockState(new BlockPos((int) x, (int) y, (int) z)).getBlock() == Blocks.WATER) {
                motionX *= 0.8;
                motionY *= 0.8;
                motionZ *= 0.8;
            } else {
                motionX *= 0.99;
                motionY *= 0.99;
                motionZ *= 0.99;
            }

            if (item instanceof BowItem) motionY -= 0.05000000074505806;
            else if (mc.player.getMainHandItem().getItem() instanceof CrossbowItem) motionY -= 0.05000000074505806;
            else motionY -= 0.03f;

            Vec3 position = new Vec3(x, y, z);

            Entity hitEntity = getHitEntity(new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1));
            HitResult possibleResult = mc.level.clip(new ClipContext(lastPosition, position, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

            if(hitEntity != null) {
                entity = hitEntity;
                landed = true;
            } else {
                if(possibleResult != null && possibleResult.getType() != HitResult.Type.MISS) {
                    result = possibleResult;
                    landed = true;
                }
            }

            Renderer3D.DEBUG_LINES.add(new Renderer3D.VertexCollection(new Renderer3D.Vertex(matrix4f, (float) (lastPosition.x - mc.gameRenderer.mainCamera().position().x), (float) (lastPosition.y - mc.gameRenderer.mainCamera().position().y), (float) (lastPosition.z - mc.gameRenderer.mainCamera().position().z), lineColor.getColor().getRGB()),
                    new Renderer3D.Vertex(matrix4f, (float) (position.x - mc.gameRenderer.mainCamera().position().x), (float) (position.y - mc.gameRenderer.mainCamera().position().y), (float) (position.z - mc.gameRenderer.mainCamera().position().z), lineColor.getColor().getRGB())));
        }

        if(result != null && result.getType() == HitResult.Type.BLOCK) {
            AABB box = new AABB(result.getLocation().x - 0.15, result.getLocation().y - 0.15, result.getLocation().z - 0.15, result.getLocation().x + 0.15, result.getLocation().y + 0.15, result.getLocation().z + 0.15);

            if (blocksMode.getValue().equalsIgnoreCase("Fill") || blocksMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(matrices, box, blocksFillColor.getColor());
            if (blocksMode.getValue().equalsIgnoreCase("Outline") || blocksMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(matrices, box, blocksOutlineColor.getColor());
        }

        if(entity != null && !hitEntities.contains(entity)) {
            if (entitiesMode.getValue().equalsIgnoreCase("Fill") || entitiesMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBox(matrices, entity.getBoundingBox(), entitiesFillColor.getColor());
            if (entitiesMode.getValue().equalsIgnoreCase("Outline") || entitiesMode.getValue().equalsIgnoreCase("Both")) Renderer3D.renderBoxOutline(matrices, entity.getBoundingBox(), entitiesOutlineColor.getColor());
            hitEntities.add(entity);
        }
    }

    private Entity getHitEntity(AABB box) {
        for(Entity entity : mc.level.entitiesForRendering()) {
            if(entity == mc.player || entity instanceof Arrow) continue;
            if (entity.getBoundingBox().intersects(box)) return entity;
        }
        return null;
    }

    private float getThrowVelocity(Item item) {
        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem) return 0.5f;
        if (item instanceof ExperienceBottleItem) return 0.59f;
        if (item instanceof TridentItem) return 2f;
        return 1.5f;
    }

    private int getThrowPitch(Item item) {
        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem || item instanceof ExperienceBottleItem)
            return 20;
        return 0;
    }
}
