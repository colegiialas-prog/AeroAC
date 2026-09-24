package dev.aeroac.neural;

import dev.aeroac.utils.anticheat.LogUtil;

/**
 * The logger the neural manager writes to.
 *
 * <p>It exists because the neural manager is constructed before the platform plugin is resolvable
 * and is exercised by unit tests that have no server at all: calling {@code LogUtil} directly would
 * make an unconfigured data folder fail as a {@code NullPointerException} in the middle of a reload
 * instead of as one readable line. Every operator-facing line that goes through here is Russian.
 */
public interface NeuralLog {

    /** One line of ordinary start-up/reload information. */
    void info(String message);

    /** A recoverable problem the operator has to know about. */
    void warn(String message);

    /** A problem that switched a feature off. */
    void error(String message, Throwable error);

    /** The real server log. */
    NeuralLog PLATFORM = new NeuralLog() {
        @Override public void info(String message) { LogUtil.info(message); }
        @Override public void warn(String message) { LogUtil.warn(message); }
        @Override public void error(String message, Throwable error) { LogUtil.error(message, error); }
    };

    /** Used by tests: keeps the assertions about state, not about a console. */
    NeuralLog SILENT = new NeuralLog() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message, Throwable error) { }
    };
}
