package eu.client.commands;

import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.utils.IMinecraft;

import java.util.Arrays;
import java.util.List;

@Getter @Setter
public abstract class Command implements IMinecraft {
    private String name, tag;
    private final String description, syntax;
    private final List<String> aliases;

    public Command() {
        RegisterCommand annotation = getClass().getAnnotation(RegisterCommand.class);

        name = annotation.name();
        tag = annotation.tag().isEmpty() ? annotation.name() : annotation.tag();
        description = annotation.description();
        syntax = annotation.syntax();
        aliases = Arrays.asList(annotation.aliases());
    }

    public abstract void execute(String[] args);

    // args is everything typed after the command name, NOT including the token currently being
    // typed -- e.g. for ".module hud dr" this is called with args=["hud"] to suggest what can
    // follow, and the caller filters the result against "dr" itself. Override per-command to offer
    // completions; default is "no suggestions" (falls back to just showing the command's syntax).
    public List<String> getSuggestions(String[] args) {
        return List.of();
    }

    protected List<String> moduleNames() {
        return EUClient.MODULE_MANAGER.getModules().stream().map(m -> m.getName().toLowerCase()).toList();
    }

    public void messageSyntax() {
        EUClient.CHAT_MANAGER.info(name + " " + syntax);
    }
}
