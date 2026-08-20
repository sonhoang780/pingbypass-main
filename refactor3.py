import sys

content = open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'r', encoding='utf-8').read()

content = content.replace('eu.client.EUClient.EVENT_BUS.register(this);', 'eu.client.EUClient.EVENT_HANDLER.subscribe(this);')

with open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'w', encoding='utf-8') as f:
    f.write(content)
