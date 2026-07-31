package eu.client.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import eu.client.utils.IMinecraft;
import eu.client.utils.text.CustomFormatting;
import eu.client.utils.text.FormattingUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(StringDecomposer.class)
public class TextVisitFactoryMixin implements IMinecraft {
    // visitFormatted(String,int,Style,Style,CharacterVisitor)Z was renamed iterateFormatted(...) and the callback interface renamed CharacterVisitor -> FormattedCharSink
    @WrapOperation(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", at = @At(value = "INVOKE", target = "Ljava/lang/String;charAt(I)C", ordinal = 1))
    private static char visitFormatted(String instance, int index, Operation<Character> original, @Local(ordinal = 2) LocalRef<Style> style) {
        CustomFormatting customFormatting = CustomFormatting.byCode(instance.charAt(index));
        if (customFormatting != null) style.set(FormattingUtils.withExclusiveFormatting(style.get(), customFormatting));

        return original.call(instance, index);
    }
}
