import sys
content = open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'r', encoding='utf-8').read()

content = content.replace('@RegisterModule(name = \"MusicHUD\", description = \"Displays music player visualizer and track info.\", category = Module.Category.VISUALS)\npublic class MusicHUDModule extends Module {', 'public class MusicHUDComponent {')

lines = content.split('\n')
new_lines = []
skip = False
for i, line in enumerate(lines):
    if 'public final PositionSetting position' in line:
        skip = True
    if skip and 'public final ColorSetting color' in line:
        skip = False
        continue
    if not skip:
        new_lines.append(line)
content = '\n'.join(new_lines)

content = content.replace('public MusicHUDModule() {', 'public MusicHUDComponent() {\n        INSTANCE = this;\n        eu.client.EUClient.EVENT_BUS.register(this);\n    }')
content = content.replace('INSTANCE = this;\n        position.set(20f, 60f);\n        HudElementRegistry.register(\"MusicHUD\", enabledSetting, position, hudCategory);', '')

content = content.replace('enabledSetting.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue()')
content = content.replace('bgMode.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBgMode.getValue()')
content = content.replace('color.getColor()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudColor.getColor()')
content = content.replace('position.getX()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getX()')
content = content.replace('position.getY()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getY()')
content = content.replace('position.getWidth()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getWidth()')
content = content.replace('position.getHeight()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudPosition.getHeight()')
content = content.replace('compactMode.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudCompactMode.getValue()')
content = content.replace('disk.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDisk.getValue()')
content = content.replace('ultraDisk.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudUltraDisk.getValue()')
content = content.replace('diskSize.getValue().floatValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudDiskSize.getValue().floatValue()')
content = content.replace('barAlpha.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBarAlpha.getValue()')
content = content.replace('gradientBars.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudGradientBars.getValue()')
content = content.replace('textBloom.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudTextBloom.getValue()')
content = content.replace('textBloomPlus.getValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudTextBloomPlus.getValue()')
content = content.replace('blurIntensity.getValue().floatValue()', 'eu.client.modules.impl.core.HUDModule.INSTANCE.musicHudBlurIntensity.getValue().floatValue()')
content = content.replace('MusicHUDModule.INSTANCE', 'MusicHUDComponent.INSTANCE')
content = content.replace('public static MusicHUDModule INSTANCE', 'public static MusicHUDComponent INSTANCE')

content = content.replace('    @Override\n    public void onEnable() {\n        enabledSetting.setValue(true);\n        lastRenderTime = System.currentTimeMillis();\n    }', '')
content = content.replace('    @Override\n    public void onDisable() {\n        enabledSetting.setValue(false);\n        paintState = null;\n    }', '')
content = content.replace('        if (eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue() != isToggled()) {\n            setToggled(eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue());\n        }', '        if (!eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue()) return;')
content = content.replace('if (!isToggled())', 'if (!eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue())')
content = content.replace('if (isToggled())', 'if (eu.client.modules.impl.core.HUDModule.INSTANCE.musicHud.getValue())')
content = content.replace('eu.client.modules.impl.visuals.MusicHUDModule', 'eu.client.modules.impl.visuals.MusicHUDComponent')
content = content.replace('import eu.client.modules.Module;\nimport eu.client.modules.RegisterModule;', '')
content = content.replace('import eu.client.settings.impl.BooleanSetting;\nimport eu.client.settings.impl.CategorySetting;\nimport eu.client.settings.impl.ColorSetting;\nimport eu.client.settings.impl.ModeSetting;\nimport eu.client.settings.impl.NumberSetting;\nimport eu.client.settings.impl.PositionSetting;', '')

with open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'w', encoding='utf-8') as f:
    f.write(content)
