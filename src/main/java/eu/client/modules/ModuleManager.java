package eu.client.modules;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import eu.client.EUClient;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.KeyInputEvent;
import eu.client.events.impl.MouseInputEvent;
import eu.client.events.impl.TickEvent;
import eu.client.settings.Setting;
import eu.client.utils.IMinecraft;
import eu.client.utils.input.KeyboardUtils;
import org.reflections.Reflections;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Getter
public class ModuleManager implements IMinecraft {
    private final List<Module> modules = new ArrayList<>();
    private final Map<Class<? extends Module>, Module> moduleClasses = new Reference2ReferenceOpenHashMap<>();

    public ModuleManager() {
        EUClient.EVENT_HANDLER.subscribe(this);

        try {
            for (Class<?> clazz : new Reflections("eu.client.modules.impl").getSubTypesOf(Module.class)) {
                if (clazz.getAnnotation(RegisterModule.class) == null) continue;
                Module module = (Module) clazz.getDeclaredConstructor().newInstance();

                for (Field field : module.getClass().getDeclaredFields()) {
                    if (!Setting.class.isAssignableFrom(field.getType())) continue;
                    if (!field.canAccess(module)) field.setAccessible(true);

                    module.getSettings().add((Setting) field.get(module));
                }

                module.getSettings().add(module.chatNotify);
                module.getSettings().add(module.drawn);
                module.getSettings().add(module.bind);

                if (module.isProxyEnhanced() && module.proxyMode != null) {
                    module.getSettings().add(module.proxyMode);
                }

                modules.add(module);
                moduleClasses.put(module.getClass(), module);
            }
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            EUClient.LOGGER.error("Failed to register the client's modules!", exception);
        }

        modules.sort(Comparator.comparing(Module::getName));
    }

    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        modules.stream()
                .filter(m -> m.getBind() == event.getKey() && m.bind.getMode().equals("Bind"))
                .forEach(m -> m.setToggled(!m.isToggled()));
    }

    @SubscribeEvent
    public void onMouseInput(MouseInputEvent event) {
        modules.stream()
                .filter(m -> m.getBind() == (-event.getButton() - 1) && m.bind.getMode().equals("Bind"))
                .forEach(m -> m.setToggled(!m.isToggled()));
    }

    // Hold: module toggled on only while the bind is physically held. ReverseHold: the inverse.
    // Runs every tick instead of off press/release events since there's no release event for
    // mouse buttons routed through EUClient's event bus, and polling is simplest for both anyway.
    @SubscribeEvent
    public void onTick(TickEvent event) {
        for (Module module : modules) {
            String mode = module.bind.getMode();
            if (mode.equals("Bind") || module.getBind() == 0) continue;

            boolean held = KeyboardUtils.isBindDown(module.getBind());
            boolean shouldBeToggled = mode.equals("Hold") == held;
            if (module.isToggled() != shouldBeToggled) module.setToggled(shouldBeToggled);
        }
    }

    public List<Module> getModules(Module.Category category) {
        return modules.stream().filter(m -> m.getCategory() == category).toList();
    }

    public Module getModule(String name) {
        return modules.stream().filter(m -> m.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModule(Class<T> clazz) {
        return (T) moduleClasses.get(clazz);
    }
}
