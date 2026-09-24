package dev.aeroac.platform.fabric.mc1194;

import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.platform.fabric.AeroACFabricIntermediaryLoaderPlugin;
import dev.aeroac.platform.fabric.mc1171.Fabric1171PlatformServer;
import net.minecraft.commands.CommandSourceStack;

public class Fabric1190PlatformServer extends Fabric1171PlatformServer {
    @Override
    public void dispatchCommand(Sender sender, String command) {
        CommandSourceStack commandSource = AeroACFabricIntermediaryLoaderPlugin.LOADER.getFabricSenderFactory().unwrap(sender);
        AeroACFabricIntermediaryLoaderPlugin.FABRIC_SERVER.getCommands().performPrefixedCommand(commandSource, command);
    }
}
