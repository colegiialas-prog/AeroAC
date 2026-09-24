package dev.aeroac.locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the interface's own sources and refuses to let English copy back in.
 *
 * <p>A catalog test can only check the words somebody remembered to add. This one checks the other
 * direction: every string a screen puts in front of an operator has to be either Russian already or a
 * key in the catalog, and a key nobody defined fails here rather than rendering itself in an
 * inventory at three in the morning.
 *
 * <p>What counts as "a string in front of an operator" is a literal inside a statement that builds
 * presentation — a menu item, a lore line, a chat reply, an alert, an exception message an operator
 * reads in the console. Configuration paths, permission nodes, JSON field names, command tokens,
 * format patterns and regular expressions are not copy and are filtered by shape: an identifier with
 * no spaces, an all-caps code, or anything carrying a {@code %} marker.
 *
 * <p>Concatenation is covered by the same rule, because every fragment of a concatenated sentence is
 * its own literal: a half-translated line leaves English fragments behind, and each one fails on its
 * own. The exceptions below are the tokens that are genuinely not copy, and each is named with the
 * reason it is exempt instead of being waved through by a rule.
 */
class LocalizationSourceTest {

    private static final List<Path> SOURCE_ROOTS = sourceRoots();

    /** Sources owned by other people, or machines rather than operators, and out of this scan's remit. */
    private static final Set<String> NOT_THIS_AUTHORS = Set.of(
            "TrainingServiceClient.java", "TrainingJob.java", "AeroCommand.java");

    /**
     * Not copy, and deliberately not translated.
     *
     * <p>Brand and the plugin's own tags, the syntax of the commands an operator types, the dataset's
     * vocabulary for cheat clients, a hash pattern, and the glyph used as a multiplier in a status
     * line. Everything else has to be Russian or a catalog key.
     */
    private static final Set<String> NOT_COPY = Set.of(
            "AERO \u203a ", "[Aero] ", "Aero: ", "REC ", "w | ", "  x", " x",
            "[A-Za-z0-9_-]{1,128}", "[A-Za-z0-9][A-Za-z0-9_.-]{0,63}",
            "/aero player ", "/aero watch ", "/aero training set ",
            "Aim Assist", "KillAura", "TriggerBot",
            "[a-f0-9]{64}");

    /** Statements that build something an operator reads. */
    private static final List<String> PRESENTATION = List.of(
            "AeroMessages.tr(", "MenuItems.", "Component.text", "reply(", "sendMessage", "title()",
            "footer(", "deny(", "Exception(");

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TR_KEY = Pattern.compile("AeroMessages\\.tr\\(\"([^\"]+)\"");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:/#-]+");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]+");
    private static final Pattern HAS_LETTERS = Pattern.compile("[A-Za-z]");
    /** A returned literal: prose handed back for somebody else to show. */
    /**
     * A returned value: prose handed back for somebody else to show, directly or wrapped.
     *
     * <p>Any {@code return}, not only {@code return "…"}. The wrapped form —
     * {@code return TrainingJob.disconnected("…")} — reached an operator's screen in English while
     * the narrower pattern looked straight past it.
     */
    private static final Pattern RETURNS = Pattern.compile("(?s)\\breturn\\b");

    @BeforeEach void loadCatalog() {
        AeroMessages.preload();
    }

    @Test
    void everyKeyTheInterfaceUsesExistsInTheCatalog() throws IOException {
        var catalog = AeroMessages.catalog();
        List<String> unknown = new ArrayList<>();
        for (Path file : sources()) {
            Matcher keys = TR_KEY.matcher(text(file));
            while (keys.find()) {
                if (!catalog.containsKey(keys.group(1))) unknown.add(file.getFileName() + ": " + keys.group(1));
            }
        }
        assertTrue(unknown.isEmpty(), "keys nothing defines would render as themselves: " + unknown);
    }

    @Test
    void noScreenStillPrintsAnEnglishSentence() throws IOException {
        List<String> leaks = new ArrayList<>();
        for (Path file : sources()) {
            String text = text(file);
            for (Span statement : statements(text)) {
                String block = text.substring(statement.start(), statement.end());
                // A statement that renders, or one that hands a finished sentence back to be
                // rendered. The second kind is how English kept slipping through: a validator that
                // returns "Pick a player to record." reaches an operator just as surely as a
                // Component built in place, and nothing about the statement looks like drawing.
                boolean renders = PRESENTATION.stream().anyMatch(block::contains);
                boolean returnsProse = RETURNS.matcher(block).find();
                if (!renders && !returnsProse) continue;
                Matcher literals = LITERAL.matcher(block);
                while (literals.find()) {
                    String value = literals.group(1);
                    if (isCopy(value)) leaks.add(file.getFileName() + ": " + value);
                }
            }
        }
        assertTrue(leaks.isEmpty(), "English copy still reaches the screen: " + leaks);
    }

    @Test
    void theScannedSourcesAreTheOnesThatDrawTheInterface() throws IOException {
        assertFalse(sources().isEmpty(), "the scan found nothing to scan, so it proves nothing");
        boolean menus = sources().stream().anyMatch(path -> path.getFileName().toString().equals("MenuItems.java"));
        assertTrue(menus, "the menu vocabulary has to be part of this scan");
    }

    private static boolean isCopy(String value) {
        if (value.isEmpty() || NOT_COPY.contains(value)) return false;
        if (value.startsWith("\u00a7") || value.contains("%")) return false;
        // Technical tokens in Russian sentences remain protocol/technology names, not English copy.
        if (value.matches("(?s).*[А-Яа-яЁё].*") && !Pattern.compile("\\b(?:Click|Enable|Disable|Training|Recording|Player|Reload|Failed|Success|Waiting|Grim)\\b").matcher(value).find()) return false;
        if (!HAS_LETTERS.matcher(value).find()) return false;
        if (CODE.matcher(value).matches()) return false;
        if (IDENTIFIER.matcher(value).matches()) return false;
        // A key is already translated by definition.
        return !AeroMessages.catalog().containsKey(value);
    }

    /** Statements split on the punctuation that ends one: a semicolon, or a block boundary. */
    private static List<Span> statements(String text) {
        List<Span> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (c == '\'') {
                i = skipChar(text, i);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i);
                i = end < 0 ? text.length() : end + 1;
                continue;
            }
            if (c == '{' || c == '}' || c == ';') {
                spans.add(new Span(start, i));
                start = i + 1;
            }
        }
        spans.add(new Span(start, text.length()));
        return spans;
    }

    private static int skipString(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (text.charAt(i) == '"') return i;
        }
        return text.length() - 1;
    }

    private static int skipChar(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (text.charAt(i) == '\'') return i;
        }
        return text.length() - 1;
    }

    private record Span(int start, int end) { }

    private static List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !NOT_THIS_AUTHORS.contains(path.getFileName().toString()))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        addIfDirectory(roots, Path.of("src", "main", "java", "dev", "aeroac", "neural", "admin"));
        addIfDirectory(roots, Path.of("..", "bukkit", "src", "main", "java", "dev", "aeroac",
                "platform", "bukkit", "admin"));
        if (roots.isEmpty()) {
            addIfDirectory(roots, Path.of("common", "src", "main", "java", "dev", "aeroac",
                    "neural", "admin"));
        }
        return roots;
    }

    private static void addIfDirectory(List<Path> roots, Path candidate) {
        if (Files.isDirectory(candidate)) roots.add(candidate);
    }

    private static String text(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
