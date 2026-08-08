package eu.client.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lombok.Getter;
import lombok.Setter;
import eu.client.EUClient;
import eu.client.commands.impl.ModuleCommand;
import eu.client.events.SubscribeEvent;
import eu.client.events.impl.ChatInputEvent;
import org.reflections.Reflections;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Rebuilt on top of com.mojang.brigadier (already bundled with the game -- vanilla's own "/"
// commands use the exact same library, no new dependency needed), matching the architecture
// homovore (dev.leonetic) uses for its own command system: a real CommandDispatcher, and a mixin
// on vanilla's CommandSuggestions (see CommandSuggestionsMixin) that feeds OUR dispatcher's parse
// into vanilla's OWN currentParse/pendingSuggestions fields whenever the typed text starts with
// OUR prefix instead of "/". That reuses ALL of vanilla's own suggestion rendering, tab-completion,
// argument highlighting, and usage-tooltip code for free -- replacing ChatScreenMixin's
// custom-built suggestion box/ghost-hint/tab-cycle code (deleted), which had its own string of
// alignment bugs earlier (wrong X/Y offsets assuming a bordered EditBox that isn't one, etc.) that
// simply don't exist in vanilla's own, already-correct implementation.
//
// This ports the SYSTEM only, not homovore's own command set -- every existing Command subclass
// under eu.client.commands.impl keeps its EXACT OLD API (execute(String[] args)/
// getSuggestions(String[] priorArgs)) completely unchanged; this class is the only thing that
// changed, registering each one as a Brigadier literal + a single greedy-string "args" argument
// that re-splits by space and delegates to that same old API. Brigadier's own literal-child
// matching already handles top-level command-NAME suggestions natively, so (unlike homovore's
// separate CommandArgumentType) nothing extra is needed for that part.
public class CommandManager {
    @Getter private final ArrayList<Command> commands = new ArrayList<>();
    @Getter @Setter private String prefix = ".";
    @Getter private final CommandDispatcher<CommandManager> dispatcher = new CommandDispatcher<>();

    public CommandManager() {
        EUClient.EVENT_HANDLER.subscribe(this);

        // ModuleCommand registered (and merged into the dispatcher) FIRST: Brigadier's addChild
        // MERGES same-named literal nodes, and the node registered LAST wins the merge (its
        // executes()/children overwrite the earlier node's) -- so an explicit @RegisterCommand
        // whose alias happens to match a module's own auto-command name (e.g. FakePlayerCommand's
        // "fakeplayer" alias vs. ModuleCommand's own module.getName().toLowerCase() == "fakeplayer")
        // needs to register SECOND to win. Registering explicit commands first (the old order) let
        // ModuleCommand silently steal the node instead: ".fakeplayer" ran ModuleCommand's own
        // bare-args branch (messageSyntax()) rather than FakePlayerCommand.execute().
        EUClient.MODULE_MANAGER.getModules().forEach(m -> commands.add(new ModuleCommand(m)));

        try {
            for (Class<?> clazz : new Reflections("eu.client.commands.impl").getSubTypesOf(Command.class)) {
                if (clazz.getAnnotation(RegisterCommand.class) == null) continue;
                if (clazz == ModuleCommand.class) continue;

                commands.add((Command) clazz.getDeclaredConstructor().newInstance());
            }
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            EUClient.LOGGER.error("Failed to register the client's modules!", exception);
        }

        for (Command command : commands) {
            register(command);
        }
    }

    private void register(Command command) {
        List<String> aliases = new ArrayList<>();
        aliases.add(command.getName());
        aliases.addAll(command.getAliases());

        for (String alias : aliases) {
            LiteralArgumentBuilder<CommandManager> builder = LiteralArgumentBuilder.literal(alias);
            builder.executes(ctx -> {
                command.execute(new String[0]);
                return 1;
            });
            builder.then(RequiredArgumentBuilder.<CommandManager, String>argument("args", StringArgumentType.greedyString())
                    .suggests((ctx, suggestionsBuilder) -> {
                        // getRemaining() is everything typed for THIS argument node so far -- same
                        // "priorArgs (already-complete tokens) + partial (still being typed)" split
                        // the old CommandManager.getSuggestions() did, just per-argument instead of
                        // on the whole raw string.
                        String remaining = suggestionsBuilder.getRemaining();
                        String[] parts = remaining.split(" ", -1);
                        String partial = parts[parts.length - 1].toLowerCase();
                        String[] priorArgs = Arrays.copyOf(parts, parts.length - 1);

                        for (String option : command.getSuggestions(priorArgs)) {
                            if (option.toLowerCase().startsWith(partial)) {
                                suggestionsBuilder.suggest(option);
                            }
                        }
                        return suggestionsBuilder.buildFuture();
                    })
                    .executes(ctx -> {
                        String args = StringArgumentType.getString(ctx, "args");
                        command.execute(args.isEmpty() ? new String[0] : args.split(" "));
                        return 1;
                    }));
            dispatcher.register(builder);
        }
    }

    @SubscribeEvent
    public void onChatInput(ChatInputEvent event) {
        String message = event.getMessage();
        if (!message.startsWith(prefix)) return;

        event.setCancelled(true);
        execute(message);
    }

    public void execute(String input) {
        try {
            dispatcher.execute(input.substring(prefix.length()), this);
        } catch (CommandSyntaxException e) {
            EUClient.CHAT_MANAGER.warn("Could not find the command specified.");
        }
    }

    public Command getCommand(String name) {
        return commands.stream().filter(c -> c.getName().equalsIgnoreCase(name.toLowerCase()) || c.getAliases().contains(name.toLowerCase())).findFirst().orElse(null);
    }
}
