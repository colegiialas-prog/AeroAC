package dev.aeroac.manager.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets one nested scalar in config text, line by line, the way {@link NeuralFlagsFile} sets the
 * recording switches: comments, ordering and every unrelated key survive, and a missing key or
 * section is inserted at the end of its parent, with the file's own indentation. Pure text in, text out.
 */
public final class YamlScalars {
    private static final Pattern VALUE_AT = Pattern.compile("^(\\s*[^:#][^:]*:\\s*)([^#\\r\\n]*?)(\\s+#[^\\r\\n]*)?(\\r?)$");

    private YamlScalars() { }

    /**
     * @param path dotted key, e.g. {@code neural.inference.mode}
     * @param value the YAML scalar exactly as it should appear (quote strings yourself if needed)
     */
    public static String set(String yaml, String path, String value) {
        if (yaml == null) return null;
        String eol = yaml.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(yaml.split("\r\n|\n", -1)));
        String[] keys = path.split("\\.");
        int unit = unit(lines);
        int from = 0, to = lines.size(), parent = -1, indent = 0;
        for (int depth = 0; depth < keys.length; depth++) {
            if (parent >= 0) indent = childIndent(lines, parent, to, indent(lines.get(parent)) + unit);
            int found = indexOf(lines, from, to, indent, keys[depth]);
            if (found < 0) {
                int insertAt = parent < 0 ? lines.size() : to;
                if (parent < 0 && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) insertAt = lines.size() - 1;
                List<String> added = new ArrayList<>();
                for (int rest = depth; rest < keys.length; rest++) {
                    String pad = " ".repeat(indent + (rest - depth) * unit);
                    added.add(rest == keys.length - 1 ? pad + keys[rest] + ": " + value : pad + keys[rest] + ":");
                }
                lines.addAll(insertAt, added);
                return String.join(eol, lines);
            }
            if (depth == keys.length - 1) {
                Matcher matcher = VALUE_AT.matcher(lines.get(found));
                if (!matcher.matches()) return null;
                String comment = matcher.group(3) == null ? "" : matcher.group(3);
                lines.set(found, matcher.group(1) + value + comment + matcher.group(4));
                return String.join(eol, lines);
            }
            parent = found;
            from = found + 1;
            to = blockEnd(lines, found);
        }
        return String.join(eol, lines);
    }

    /** The scalar at {@code path} without quotes or comment, or null when the key is absent. */
    public static String get(String yaml, String path) {
        if (yaml == null) return null;
        List<String> lines = List.of(yaml.split("\r\n|\n", -1));
        String[] keys = path.split("\\.");
        int unit = unit(lines);
        int from = 0, to = lines.size(), parent = -1, indent = 0;
        for (int depth = 0; depth < keys.length; depth++) {
            if (parent >= 0) indent = childIndent(lines, parent, to, indent(lines.get(parent)) + unit);
            int found = indexOf(lines, from, to, indent, keys[depth]);
            if (found < 0) return null;
            if (depth == keys.length - 1) {
                Matcher matcher = VALUE_AT.matcher(lines.get(found));
                return matcher.matches() ? matcher.group(2).trim().replaceAll("^[\"']|[\"']$", "") : null;
            }
            parent = found;
            from = found + 1;
            to = blockEnd(lines, found);
        }
        return null;
    }

    private static int indent(String line) { return line.length() - line.stripLeading().length(); }

    /** The file's indentation step: the first indented key's indent, 2 when nothing is indented. */
    private static int unit(List<String> lines) {
        for (String line : lines) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (indent(line) > 0) return indent(line);
        }
        return 2;
    }

    /** Indent of the first key inside a block, or {@code fallback} for an empty block. */
    private static int childIndent(List<String> lines, int parent, int to, int fallback) {
        for (int index = parent + 1; index < to; index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            return indent(line);
        }
        return fallback;
    }

    private static int indexOf(List<String> lines, int from, int to, int indent, String key) {
        Pattern pattern = Pattern.compile("^ {" + indent + "}" + Pattern.quote(key) + ":(\\s.*)?$");
        for (int index = Math.max(0, from); index < Math.min(to, lines.size()); index++) {
            if (pattern.matcher(lines.get(index)).matches()) return index;
        }
        return -1;
    }

    /**
     * First index after {@code keyIndex} whose indentation leaves that key's block. Trailing blank
     * and comment lines belong to whatever follows, so an inserted key lands right after the
     * block's last real line.
     */
    private static int blockEnd(List<String> lines, int keyIndex) {
        int indent = indent(lines.get(keyIndex));
        int end = lines.size();
        for (int index = keyIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (indent(line) <= indent) { end = index; break; }
        }
        while (end - 1 > keyIndex && (lines.get(end - 1).isBlank() || lines.get(end - 1).stripLeading().startsWith("#"))) end--;
        return end;
    }
}
