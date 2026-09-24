package dev.aeroac.platform.bukkit.command;

import dev.aeroac.platform.api.command.PlayerSelector;
import org.incendo.cloud.bukkit.parser.selector.SinglePlayerSelectorParser;
import org.incendo.cloud.parser.ParserDescriptor;

import java.util.concurrent.CompletableFuture;

public class BukkitPlayerSelectorParser<C> {

    public ParserDescriptor<C, PlayerSelector> descriptor() {
        ParserDescriptor<C, ?> descriptor = SinglePlayerSelectorParser.singlePlayerSelectorParser();
        return ParserDescriptor.of(
                descriptor.parser().mapSuccess((context, selector) -> CompletableFuture.completedFuture(
                        new BukkitPlayerSelectorAdapter((org.incendo.cloud.bukkit.data.SinglePlayerSelector) selector)
                )),
                PlayerSelector.class
        );
    }
}
