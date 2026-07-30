package eu.client.utils.text;

import lombok.Getter;
import net.minecraft.util.StringRepresentable;

@Getter
public enum CustomFormatting implements StringRepresentable {
    CLIENT('z'), RAINBOW('y');

    private final char code;

    CustomFormatting(char code) {
        this.code = code;
    }

    public String getName() {
        return this.name().toLowerCase();
    }

    public static CustomFormatting byCode(char code) {
        char c = Character.toLowerCase(code);

        for (CustomFormatting formatting : values()) {
            if (formatting.code == c) {
                return formatting;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return "§" + code;
    }

    @Override
    public String getSerializedName() {
        return null;
    }
}