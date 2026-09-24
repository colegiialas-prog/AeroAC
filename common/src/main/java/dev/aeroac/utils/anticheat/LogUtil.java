package dev.aeroac.utils.anticheat;

import dev.aeroac.AeroAPI;
import ac.grim.grimac.api.plugin.GrimPlugin;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;

@UtilityClass
public class LogUtil {
    public void info(final String info) {
        getLogger().info(info);
    }

    public void warn(final String warn) {
        getLogger().warning(warn);
    }

    public void warn(final String description, final Throwable throwable) {
        Logger logger = getLogger();
        if (logger != null) {
            logger.warning(description + ": " + getStackTrace(throwable));
        } else {
            throwable.printStackTrace();
        }
    }

    public void error(final String error) {
        getLogger().severe(error);
    }

    @SuppressWarnings("CallToPrintStackTrace")
    public void error(final String description, final Throwable throwable) {
        Logger logger = getLogger();
        if (logger != null) {
            logger.severe(description + ": " + getStackTrace(throwable));
        } else {
            throwable.printStackTrace();
        }
    }

    @SuppressWarnings("CallToPrintStackTrace")
    public void error(final Throwable throwable) {
        Logger logger = getLogger();
        if (logger != null) {
            logger.severe(getStackTrace(throwable));
        } else {
            throwable.printStackTrace();
        }
    }

    /**
     * The plugin's logger, or a standalone fallback while the API is still coming up.
     *
     * <p>Logging is the one call that must work at every point in a lifecycle, including the parts
     * that run before the API has a loader and the parts that run after it has been torn down. This
     * used to dereference the loader directly, so a single log line on the load path — the data
     * folder migration, for one — threw and aborted the whole plugin before it could start.
     */
    public Logger getLogger() {
        try {
            GrimPlugin plugin = AeroAPI.INSTANCE.getGrimPlugin();
            if (plugin != null) {
                Logger logger = plugin.getLogger();
                if (logger != null) return logger;
            }
        } catch (RuntimeException | LinkageError tooEarly) {
            // Falls through: the API is not up, which is not a reason to lose the message.
        }
        return FALLBACK;
    }

    private final Logger FALLBACK = Logger.getLogger("AeroAC");

    public void console(final String info) {
        AeroAPI.INSTANCE.getPlatformServer().getConsoleSender().sendMessage(MessageUtil.translateAlternateColorCodes('&', info));
    }

    public void console(final Component info) {
        AeroAPI.INSTANCE.getPlatformServer().getConsoleSender().sendMessage(info);
    }

    private static String getStackTrace(Throwable throwable) {
        String message = throwable.getMessage();
        try (StringWriter sw = new StringWriter()) {
            try (PrintWriter pw = new PrintWriter(sw)) {
                throwable.printStackTrace(pw);
                message = sw.toString();
            }
        } catch (Exception ignored) {
        }
        return message;
    }

}
