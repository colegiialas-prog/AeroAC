package dev.aeroac.platform.fabric.utils.message;

import dev.aeroac.platform.api.sender.Sender;

public interface IFabricMessageUtil {
    Object textLiteral(String message);

    void sendMessage(Sender target, Object message, boolean overlay);
}
