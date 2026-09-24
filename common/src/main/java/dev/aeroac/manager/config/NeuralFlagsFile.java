package dev.aeroac.manager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Editing is line based and pure ({@link #apply(String, boolean)} takes text and returns text), so
 * a regression test can pin the exact behaviour without a server, a config library or a disk.
 */
public final class NeuralFlagsFile {

    /** Master switch: without it the whole neural module is off. */
    public static final String MASTER_FLAG = "neural.enabled";

    /** Collection switch: without it nothing is recorded. */
    public static final String COLLECTION_FLAG = "neural.collection.enabled";

    private static final String ROOT_KEY = "neural";
    private static final String ENABLED_KEY = "enabled";
    private static final String COLLECTION_KEY = "collection";

    private static final int ROOT_INDENT = 0;
    private static final int CHILD_INDENT = 2;
    private static final int GRANDCHILD_INDENT = 4;

    /**
     * {@code key:} at exactly the requested indent, with or without a value and with or without a
     * trailing comment. The colon has to follow the key immediately, so {@code enabled:} never
     * matches {@code enabled-extra:}, and the exact indent means a child of a child is not mistaken
     * for a child.
     */
    private static Pattern keyAt(int indent, String key) {
        return Pattern.compile("^ {" + indent + "}" + Pattern.quote(key) + ":(\\s.*)?$");
    }

    /** {@code key: value} at exactly the requested indent; group 1 keeps indentation, key and colon. */
    private static final Pattern VALUE_AT = Pattern.compile("^(\\s*[^:#][^:]*:\\s*)([^#\\r\\n]*?)(\\s+#[^\\r\\n]*)?(\\r?)$");

    private NeuralFlagsFile() { }

    /**
     * Applies both switches to the given config text.
     *
     * @return the new text, or {@code null} when the text cannot hold the flags (which the caller
     *         must report instead of pretending the write happened)
     */
    public static String apply(String yaml, boolean enabled) {
        if (yaml == null) return null;
        String eol = yaml.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(yaml.split("\r\n|\n", -1)));

        int neural = indexOf(lines, 0, lines.size(), ROOT_INDENT, ROOT_KEY);
        if (neural < 0) {
            // No neural block at all: append one rather than refusing, so a stripped config can
            // still be switched on from the interface.
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
            lines.add("neural:");
            lines.add("  enabled: " + enabled);
            lines.add("  collection:");
            lines.add("    enabled: " + enabled);
            return String.join(eol, lines);
        }

        int engine = blockEnd(lines, neural);
        int master = indexOf(lines, neural + 1, engine, CHILD_INDENT, ENABLED_KEY);
        if (master >= 0) {
            lines.set(master, setValue(lines.get(master), enabled));
        } else {
            lines.add(neural + 1, "  " + ENABLED_KEY + ": " + enabled);
        }

        engine = blockEnd(lines, neural);
        int collection = indexOf(lines, neural + 1, engine, CHILD_INDENT, COLLECTION_KEY);
        if (collection < 0) {
            int insertAt = blockEnd(lines, neural);
            lines.add(insertAt, "  " + COLLECTION_KEY + ":");
            lines.add(insertAt + 1, "    " + ENABLED_KEY + ": " + enabled);
        } else {
            int collectionEnd = blockEnd(lines, collection);
            int collectionEnabled = indexOf(lines, collection + 1, collectionEnd, GRANDCHILD_INDENT, ENABLED_KEY);
            if (collectionEnabled >= 0) {
                lines.set(collectionEnabled, setValue(lines.get(collectionEnabled), enabled));
            } else {
                lines.add(collection + 1, "    " + ENABLED_KEY + ": " + enabled);
            }
        }

        return String.join(eol, lines);
    }

    /** True when the text already carries both switches with exactly this value. */
    public static boolean applied(String yaml, boolean enabled) {
        if (yaml == null) return false;
        List<String> lines = List.of(yaml.split("\r\n|\n", -1));
        int neural = indexOf(lines, 0, lines.size(), ROOT_INDENT, ROOT_KEY);
        if (neural < 0) return false;
        int engine = blockEnd(lines, neural);
        int master = indexOf(lines, neural + 1, engine, CHILD_INDENT, ENABLED_KEY);
        if (master < 0 || !reads(lines.get(master)).equals(String.valueOf(enabled))) return false;
        int collection = indexOf(lines, neural + 1, engine, CHILD_INDENT, COLLECTION_KEY);
        if (collection < 0) return false;
        int collectionEnd = blockEnd(lines, collection);
        int collectionEnabled = indexOf(lines, collection + 1, collectionEnd, GRANDCHILD_INDENT, ENABLED_KEY);
        return collectionEnabled >= 0 && reads(lines.get(collectionEnabled)).equals(String.valueOf(enabled));
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

    /** Reads the value of a {@code key: value} line, or an empty string when it has none. */
    private static String reads(String line) {
        Matcher matcher = VALUE_AT.matcher(line);
        if (!matcher.matches()) return "";
        return matcher.group(2).trim();
    }

    private static String setValue(String line, boolean value) {
        Matcher matcher = VALUE_AT.matcher(line);
        if (!matcher.matches()) return line;
        String comment = matcher.group(3) == null ? "" : matcher.group(3);
        return matcher.group(1) + value + comment + matcher.group(4);
    }

    /** First index in {@code [from, to)} holding {@code key:} at exactly {@code indent}. */
    private static int indexOf(List<String> lines, int from, int to, int indent, String key) {
        Pattern pattern = keyAt(indent, key);
        for (int index = Math.max(0, from); index < Math.min(to, lines.size()); index++) {
            if (pattern.matcher(lines.get(index)).matches()) return index;
        }
        return -1;
    }

    /** First index after {@code keyIndex} that leaves the indented block of that key. */
    private static int blockEnd(List<String> lines, int keyIndex) {
        for (int index = keyIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (!line.startsWith(" ")) return index;
        }
        return lines.size();
    }
}
