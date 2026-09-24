package dev.aeroac.command;

import dev.aeroac.command.commands.*;
import dev.aeroac.command.handler.AeroCommandFailureHandler;
import dev.aeroac.platform.api.command.CommandService;
import dev.aeroac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import dev.aeroac.platform.api.sender.Sender;
import io.leangen.geantyref.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.processors.requirements.RequirementApplicable;
import org.incendo.cloud.processors.requirements.RequirementApplicable.RequirementApplicableFactory;
import org.incendo.cloud.processors.requirements.RequirementPostprocessor;
import org.incendo.cloud.processors.requirements.Requirements;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

public class CloudCommandService implements CommandService {

    public static final CloudKey<Requirements<Sender, SenderRequirement>> REQUIREMENT_KEY
            = CloudKey.of("requirements", new TypeToken<>() {});

    public static final RequirementApplicableFactory<Sender, SenderRequirement> REQUIREMENT_FACTORY
            = RequirementApplicable.factory(REQUIREMENT_KEY);

    private boolean commandsRegistered = false;

    private final Supplier<CommandManager<Sender>> commandManagerSupplier;
    private final CloudPlatformCommandArguments commandArguments;

    public CloudCommandService(Supplier<CommandManager<Sender>> commandManagerSupplier, CloudPlatformCommandArguments commandArguments) {
        this.commandManagerSupplier = commandManagerSupplier;
        this.commandArguments = commandArguments;
    }

    public void registerCommands() {
        if (commandsRegistered) return;
        CommandManager<Sender> commandManager = commandManagerSupplier.get();
        new AeroPerf().register(commandManager, commandArguments);
        new AeroDebug().register(commandManager, commandArguments);
        new AeroAlerts().register(commandManager, commandArguments);
        new AeroProfile().register(commandManager, commandArguments);
        new dev.aeroac.neural.command.NeuralCommand().register(commandManager, commandArguments);
        new dev.aeroac.neural.admin.command.AeroCommand().register(commandManager, commandArguments);
        new AeroSendAlert().register(commandManager, commandArguments);
        new AeroHelp().register(commandManager, commandArguments);
        new AeroHistory().register(commandManager, commandArguments);
        new AeroHistoryMigrate().register(commandManager, commandArguments);
        new AeroHistoryCopy().register(commandManager, commandArguments);
        new AeroReload().register(commandManager, commandArguments);
        new AeroSpectate().register(commandManager, commandArguments);
        new AeroStopSpectating().register(commandManager, commandArguments);
        new AeroLog().register(commandManager, commandArguments);
        new AeroVerbose().register(commandManager, commandArguments);
        new AeroVersion().register(commandManager, commandArguments);
        new AeroDump().register(commandManager, commandArguments);
        new AeroBrands().register(commandManager, commandArguments);
        new AeroList().register(commandManager, commandArguments);
        new AeroTestWebhook().register(commandManager, commandArguments);

        final RequirementPostprocessor<Sender, SenderRequirement>
                senderRequirementPostprocessor = RequirementPostprocessor.of(
                REQUIREMENT_KEY,
                new AeroCommandFailureHandler()
        );
        commandManager.registerCommandPostProcessor(senderRequirementPostprocessor);
        registerInvalidSyntaxHandler(commandManager);
        commandsRegistered = true;
    }

    private void registerInvalidSyntaxHandler(CommandManager<Sender> commandManager) {
        commandManager.exceptionController().registerHandler(InvalidSyntaxException.class, context -> {
            Sender sender = context.context().sender();
            if (isHistoryInput(context.context().rawInput().input())) {
                sender.sendMessage(Component.text("Неверный синтаксис команды истории.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Использование: /aero history <игрок> [page <N>]", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Использование: /aero history <игрок> session <N|latest> [page <N>] [-d] [-v]", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Подсказка: /aero history <игрок> session показывает фильтры и детали.", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Используйте /aero history player <игрок> ..., если ник совпадает с подкомандой истории.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text(context.exception().correctSyntax(), NamedTextColor.RED));
        });
    }

    private static boolean isHistoryInput(String rawInput) {
        String input = rawInput.strip();
        if (input.startsWith("/")) input = input.substring(1).strip();
        String[] tokens = input.toLowerCase(Locale.ROOT).split("\\s+");
        return tokens.length >= 2
                && (tokens[0].equals("aero") || tokens[0].equals("aeroac"))
                && (tokens[1].equals("history") || tokens[1].equals("hist"));
    }

    protected <E extends Exception> void registerExceptionHandler(CommandManager<Sender> commandManager, Class<E> ex, Function<E, ComponentLike> toComponent) {
        commandManager.exceptionController().registerHandler(ex,
                (c) -> c.context().sender().sendMessage(toComponent.apply(c.exception()).asComponent().colorIfAbsent(NamedTextColor.RED))
        );
    }
}
