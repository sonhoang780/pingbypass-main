package eu.client.utils.text;

import eu.client.EUClient;
import eu.client.mixins.accessors.StyleAccessor;
import eu.client.mixins.accessors.TextColorAccessor;
import eu.client.utils.color.ColorUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

public class FormattingUtils {
    public static String[] FORMATS = new String[]{"White", "Black", "Blue", "Dark Blue", "Green", "Dark Green", "Aqua", "Dark Aqua", "Red", "Dark Red", "Light Purple", "Dark Purple", "Yellow", "Gold", "Gray", "Dark Gray", "Client", "Rainbow"};

    public static Style withExclusiveFormatting(Style style, CustomFormatting formatting) {
        TextColor textColor = style.getColor();
        if (formatting == CustomFormatting.CLIENT) {
            textColor = TextColorAccessor.create(ColorUtils.getGlobalColor().getRGB(), "CLIENT");
        } else if(formatting == CustomFormatting.RAINBOW) {
            textColor = TextColorAccessor.create(ColorUtils.getGlobalColor().getRGB(), "RAINBOW");
        }

        return StyleAccessor.create(textColor, null, false, false, false, false, false, style.getClickEvent(), style.getHoverEvent(), style.getInsertion(), style.getFont());
    }

    // PORT (26.2): ChatFormatting no longer implements StringRepresentable (confirmed via real
    // 26.2 source, `enum ChatFormatting` has no `implements` clause at all now) -- every caller
    // only ever concatenates the result into a String (relies on toString() giving the section-sign
    // code, unaffected by this), so the common return type just needs to compile, not carry any
    // real API contract. Object is the simplest common supertype of ChatFormatting/CustomFormatting.
    public static Object getFormatting(String str) {
        return switch (str.toLowerCase()) {
            case "black" -> ChatFormatting.BLACK;
            case "blue" -> ChatFormatting.BLUE;
            case "dark blue" -> ChatFormatting.DARK_BLUE;
            case "green" -> ChatFormatting.GREEN;
            case "dark green" -> ChatFormatting.DARK_GREEN;
            case "aqua" -> ChatFormatting.AQUA;
            case "dark aqua" -> ChatFormatting.DARK_AQUA;
            case "red" -> ChatFormatting.RED;
            case "dark red" -> ChatFormatting.DARK_RED;
            case "light purple" -> ChatFormatting.LIGHT_PURPLE;
            case "dark purple" -> ChatFormatting.DARK_PURPLE;
            case "yellow" -> ChatFormatting.YELLOW;
            case "gold" -> ChatFormatting.GOLD;
            case "gray" -> ChatFormatting.GRAY;
            case "dark gray" -> ChatFormatting.DARK_GRAY;
            case "client" -> CustomFormatting.CLIENT;
            case "rainbow" -> CustomFormatting.RAINBOW;
            default -> ChatFormatting.WHITE;
        };
    }

    public static List<String> wrapText(String text, int width) {
        List<String> wrappedText = new ArrayList<>();
        String[] words = text.split(" ");
        String current = "";

        for(String word : words) {
            if(EUClient.FONT_MANAGER.getWidth(current) + EUClient.FONT_MANAGER.getWidth(word) <= width) {
                current += word + " ";
            } else {
                wrappedText.add(current);
                current = word + " ";
            }
        }
        if(EUClient.FONT_MANAGER.getWidth(current) > 0) wrappedText.add(current);

        return wrappedText;
    }

    public static String[] formatSeconds(long seconds) {
        String h = String.format("%02d", seconds/3600);
        String m = String.format("%02d", (seconds%3600)/60);
        String s = String.format("%02d", seconds%60);
        return new String[] {h,m,s};
    }
}