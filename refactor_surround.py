import sys

content = open('src/main/java/eu/client/modules/impl/combat/SurroundModule.java', 'r', encoding='utf-8').read()

content = content.replace('rotate.getValue(), crystalDestruction.getValue(), render.getValue()', 'false, crystalDestruction.getValue(), render.getValue()')
content = content.replace('rotate.getValue(), false, render.getValue()', 'false, false, render.getValue()')

target = '''        isWorking = true;
        try {
            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);'''
            
replacement = '''        isWorking = true;
        try {
            if (rotate.getValue()) {
                eu.client.EUClient.ROTATION_MANAGER.silentRotate(mc.player.getYRot(), 90f);
            }
            InventoryUtils.switchSlot(autoSwitch.getValue(), slot, previousSlot);'''

content = content.replace(target, replacement)

with open('src/main/java/eu/client/modules/impl/combat/SurroundModule.java', 'w', encoding='utf-8') as f:
    f.write(content)
