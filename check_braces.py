content = open('src/main/java/eu/client/modules/impl/visuals/MusicHUDComponent.java', 'r', encoding='utf-8').read()
level = 0
for i, c in enumerate(content):
    if c == '{': level += 1
    elif c == '}':
        level -= 1
        if level == 0:
            print(f"Class closed at index {i}, line {content[:i].count('\n') + 1}")
