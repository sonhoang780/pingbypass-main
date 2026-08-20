import sys

content = open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'r', encoding='utf-8').read()

content = content.replace('eu.client.utils.minecraft.IMinecraft', 'eu.client.utils.IMinecraft')

with open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'w', encoding='utf-8') as f:
    f.write(content)
