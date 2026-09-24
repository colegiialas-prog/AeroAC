package dev.aeroac.platform.fabric.mc1205.convert;

import dev.aeroac.platform.api.sender.Sender;
import dev.aeroac.platform.fabric.mc1194.convert.Fabric1190MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public class Fabric1200MessageUtil extends Fabric1190MessageUtil {
    @Override
    public void sendMessage(Sender target, Object message, boolean overlay) {
        ((CommandSourceStack) (Object) target).sendSuccess(() -> (Component) message, overlay);
    }
}
