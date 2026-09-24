package dev.aeroac.utils.anticheat;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Logging has to survive the parts of the lifecycle where the API is not up.
 *
 * <p>This exists because it did not. The data folder migration runs on load, before
 * {@code AeroAPI} has a loader, and its first log line dereferenced that loader: the plugin threw
 * in {@code onLoad}, never initialised, and then failed again on enable and on disable. The server
 * started perfectly well with no anticheat on it, which is the worst shape that failure can take.
 *
 * <p>So: a logger is always available, and a message on the load path is never the thing that stops
 * the plugin from loading.
 */
class LogUtilEarlyLifecycleTest {

    @Test void aLoggerIsAvailableBeforeTheApiIsInitialised() {
        Logger logger = assertDoesNotThrow(LogUtil::getLogger,
                "getLogger must not throw while the API is still coming up");
        assertNotNull(logger);
    }

    @Test void everyLogLevelIsSafeOnTheLoadPath() {
        assertDoesNotThrow(() -> LogUtil.info("load-path info"));
        assertDoesNotThrow(() -> LogUtil.warn("load-path warning"));
        assertDoesNotThrow(() -> LogUtil.error("load-path error"));
        assertDoesNotThrow(() -> LogUtil.warn("load-path warning", new IllegalStateException("cause")));
        assertDoesNotThrow(() -> LogUtil.error("load-path error", new IllegalStateException("cause")));
        assertDoesNotThrow(() -> LogUtil.error(new IllegalStateException("cause")));
    }
}
