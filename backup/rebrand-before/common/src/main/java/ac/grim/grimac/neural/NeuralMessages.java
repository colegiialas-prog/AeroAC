package ac.grim.grimac.neural;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;

/**
 * Replies are produced on the player event loop but delivered on the receiver's own scheduler,
 * so no message send happens inline on a packet thread.
 */
public final class NeuralMessages {
    private NeuralMessages() { }

    public static void send(Sender sender, String message) {
        if (sender == null || message == null) return;
        Runnable deliver = () -> {
            if (sender.isValid()) sender.sendMessage(Component.text(message));
        };
        PlatformPlayer platform = sender.getPlatformPlayer();
        if (platform != null) {
            GrimAPI.INSTANCE.getScheduler().getEntityScheduler()
                    .execute(platform, GrimAPI.INSTANCE.getGrimPlugin(), deliver, null, 1);
        } else {
            deliver.run();
        }
    }
}
