package dev.aeroac.manager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the two recording switches into the canonical {@code config.yml}.
 *
 * <p>The administrator interface toggles recording through the same file the operator edits by hand,
 * so there is exactly one source of truth and one reload path. Writing it needs to be surgical:
 * an operator's comments, ordering, blank lines and every unrelated key survive untouched, because
 * the alternative — re-serialising the YAML — would rewrite the whole file and lose them.
 *
 * <p>Both flags are always written together. Recording is {@code neural.enabled AND
 * neural.collection.enabled}, so a toggle that wrote only one of them would leave the interface
 * claiming to be on while the module stayed off. They are set to the same value, which is also what
 * makes "disabled" mean fully disabled: no collection, no inference, no risk.
 *
 * <p>Editing is line based ({@link YamlScalars}) and pure ({@link #apply(String, boolean)} takes text and returns text), so
 * a regression test can pin the exact behaviour without a server, a config library or a disk.
 */
public final class NeuralFlagsFile {

    /** Master switch: without it the whole neural module is off. */
    public static final String MASTER_FLAG = "neural.enabled";

    /** Collection switch: without it nothing is recorded. */
    public static final String COLLECTION_FLAG = "neural.collection.enabled";

    private NeuralFlagsFile() { }

    /**
     * Applies both switches to the given config text.
     *
     * @return the new text, or {@code null} when the text cannot hold the flags (which the caller
     *         must report instead of pretending the write happened)
     */
    public static String apply(String yaml, boolean enabled) {
        if (yaml == null) return null;
        // Indentation is detected, not assumed: the shipped config indents by four spaces, a
        // hand-written one often by two, and inserting a key at the wrong depth breaks the file.
        String updated = YamlScalars.set(yaml, MASTER_FLAG, String.valueOf(enabled));
        return updated == null ? null : YamlScalars.set(updated, COLLECTION_FLAG, String.valueOf(enabled));
    }

    /** True when the text already carries both switches with exactly this value. */
    public static boolean applied(String yaml, boolean enabled) {
        String value = String.valueOf(enabled);
        return value.equals(YamlScalars.get(yaml, MASTER_FLAG)) && value.equals(YamlScalars.get(yaml, COLLECTION_FLAG));
    }

    /**
     * Writes text to a file through a temporary sibling, so a crash mid-write cannot leave a config
     * that no longer parses.
     */
    public static void writeAtomically(Path file, String content) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        if (directory != null) Files.createDirectories(directory);
        Path temporary = file.resolveSibling(file.getFileName() + ".aeroac.tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
