import sys

content = open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'r', encoding='utf-8').read()

content = content.replace('public class MusicHUDComponent {', 'public class MusicHUDComponent implements eu.client.utils.minecraft.IMinecraft {')
content = content.replace('(!isToggled() && !eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue())', '(!eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue())')
content = content.replace('diskSize.getValue().intValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDiskSize.getValue().intValue()')

with open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'w', encoding='utf-8') as f:
    f.write(content)
