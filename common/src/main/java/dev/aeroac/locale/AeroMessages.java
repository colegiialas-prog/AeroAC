package dev.aeroac.locale;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Presentation translation for the Aero administration interface.
 *
 * <p>Every word an operator reads — menu titles, item names, lore, state words, quality words,
 * dataset labels, assist strength, the training centre — comes from here. Nothing a screen draws is
 * written as an English literal in a menu class any more: the menu asks for a key, and this class
 * answers with the locale's wording.
 *
 * <p>The catalog is a flat bundled resource, {@code /lang/<locale>.yml}, loaded once and cached in
 * memory. Resolution never touches the network and never touches the disk after the first load, so a
 * redraw (which happens once a second on a live screen) is a hash lookup and nothing else. The load
 * itself is a single small classpath read: call {@link #preload()} from the platform's asynchronous
 * bootstrap to take even that off the main thread.
 *
 * <p>Keys are grouped by area, {@code gui.*} for the inventory screens, {@code gui.training.*} for
 * the training centre, {@code cmd.aero.*} for the text commands, {@code admin.*} for the shared
 * vocabulary. A key with no entry resolves to itself: a missing translation degrades to a readable
 * identifier in the interface instead of an empty line, which is what an operator staring at a live
 * incident needs. {@code en_US.yml} carries the same keys in English, both as the reference wording
 * and so a coverage test can tell a translated catalog from one that was silently left alone.
 *
 * <p>Internal vocabulary is deliberately not translated here: enum constants ({@code CLEAN},
 * {@code SUSPICIOUS}, {@code LEGIT}, {@code CHEAT}, {@code UNLABELED}), JSON field names, config
 * paths, permission nodes and the data written to the dataset stay exactly as they are, because they
 * are read back by the model and by the offline audit, not by a person.
 */
public final class AeroMessages {

    /** The locale the interface ships in. Russian, and Russian only, by product decision. */
    public static final String DEFAULT_LOCALE = "ru_RU";

    /** Locale the English reference catalog belongs to. Never used for display unless asked for. */
    public static final String REFERENCE_LOCALE = "en_US";

    /** System property that can point the interface at another bundled catalog. */
    public static final String LOCALE_PROPERTY = "aeroac.locale";

    private static final String RESOURCE_DIRECTORY = "/lang/";
    private static final String RESOURCE_SUFFIX = ".yml";

    private static final AtomicReference<Catalog> ACTIVE =
            new AtomicReference<>(Catalog.empty(DEFAULT_LOCALE));

    private static final java.util.concurrent.atomic.AtomicBoolean LOADED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private AeroMessages() { }

    /** One loaded catalog: the locale it belongs to and its flat key/value table. */
    private static final class Catalog {
        private final String locale;
        private final Map<String, String> entries;
        private final Map<String, String> view;

        private Catalog(String locale, Map<String, String> entries) {
            this.locale = locale;
            this.entries = entries;
            this.view = Collections.unmodifiableMap(entries);
        }

        private static Catalog empty(String locale) {
            return new Catalog(locale, Collections.emptyMap());
        }
    }

    /**
     * The wording for one key in the active locale.
     *
     * @param key catalog key, e.g. {@code gui.main.players}
     * @return the translation, or the key itself when the catalog has no entry for it
     */
    public static String tr(String key) {
        if (key == null || key.isEmpty()) return "";
        ensureLoaded();
        Catalog catalog = ACTIVE.get();
        String value = catalog.entries.get(key);
        if (value != null) return value;
        value = fallback(catalog.locale).get(key);
        return value != null ? value : key;
    }

    /**
     * The wording for one key with {@code %s} placeholders filled in.
     *
     * <p>Values are never translated as format strings by the JVM: {@link String#format} runs with
     * {@link java.util.Locale#ROOT} so a decimal or a percentage cannot change shape because the
     * server's locale happens to be different from the plugin's.
     */
    public static String tr(String key, Object... args) {
        String pattern = tr(key);
        if (args == null || args.length == 0) return pattern;
        try {
            return String.format(java.util.Locale.ROOT, pattern, args);
        } catch (RuntimeException malformed) {
            // A bad placeholder must not take a screen down; the raw wording is still readable.
            return pattern;
        }
    }

    /** Whether the active catalog (or its English reference) knows this key. */
    public static boolean has(String key) {
        if (key == null) return false;
        ensureLoaded();
        if (ACTIVE.get().entries.containsKey(key)) return true;
        return fallback(ACTIVE.get().locale).containsKey(key);
    }

    /** The locale the interface is currently speaking. */
    public static String locale() {
        return ACTIVE.get().locale;
    }

    /** Every key in the active catalog, for diagnostics and coverage screens. */
    public static Set<String> keys() {
        ensureLoaded();
        return Collections.unmodifiableSet(ACTIVE.get().entries.keySet());
    }

    /** The active catalog as an unmodifiable view. */
    public static Map<String, String> catalog() {
        ensureLoaded();
        return ACTIVE.get().view;
    }

    /**
     * Loads the configured catalog. Safe to call from any thread and safe to call twice; the second
     * call is a no-op when the requested locale is already active.
     *
     * <p>Worth calling from the platform's asynchronous bootstrap so no inventory screen ever pays
     * for the read.
     */
    public static void preload() {
        ensureLoaded();
    }

    /**
     * Loads the catalog the first time anything asks for it.
     *
     * <p>A screen may be opened before the platform's asynchronous bootstrap has run, so the first
     * lookup loads rather than waiting to be told to. It happens once, it is guarded, and afterwards
     * every call is a map lookup. {@link #preload()} exists to move even that once off the main
     * thread, not to make the catalog work at all.
     */
    private static void ensureLoaded() {
        if (LOADED.get()) return;
        synchronized (AeroMessages.class) {
            if (LOADED.get()) return;
            LOADED.set(true);
            String locale = localeFromEnvironment();
            Map<String, String> entries = read(locale);
            if (entries.isEmpty() && !DEFAULT_LOCALE.equals(locale)) entries = read(DEFAULT_LOCALE);
            if (!entries.isEmpty()) ACTIVE.set(new Catalog(locale, entries));
        }
    }

    /**
     * Switches the active catalog, loading it from the classpath. An unknown locale leaves the
     * interface on the current wording rather than blanking it.
     *
     * @return the locale actually in effect afterwards
     */
    public static String setLocale(String locale) {
        if (locale == null || locale.isBlank()) return locale();
        if (locale.equals(locale())) return locale();
        Map<String, String> entries = read(locale);
        if (entries.isEmpty()) return locale();
        ACTIVE.set(new Catalog(locale, entries));
        return locale;
    }

    /** Locale to start in: the system property when set, otherwise {@link #DEFAULT_LOCALE}. */
    public static String localeFromEnvironment() {
        String requested = System.getProperty(LOCALE_PROPERTY);
        if (requested == null || requested.isBlank()) return DEFAULT_LOCALE;
        return read(requested).isEmpty() ? DEFAULT_LOCALE : requested;
    }

    private static void load(String locale) {
        Map<String, String> entries = read(locale);
        if (entries.isEmpty() && !DEFAULT_LOCALE.equals(locale)) entries = read(DEFAULT_LOCALE);
        if (entries.isEmpty()) return;
        ACTIVE.set(new Catalog(locale, entries));
    }

    private static Map<String, String> fallback(String locale) {
        if (REFERENCE_LOCALE.equals(locale)) return Collections.emptyMap();
        return read(REFERENCE_LOCALE);
    }

    private static Map<String, String> read(String locale) {
        Catalog current = ACTIVE.get();
        if (current.locale.equals(locale) && !current.entries.isEmpty()) return current.entries;
        String path = RESOURCE_DIRECTORY + locale + RESOURCE_SUFFIX;
        try (InputStream stream = AeroMessages.class.getResourceAsStream(path)) {
            if (stream == null) return Collections.emptyMap();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        } catch (IOException | RuntimeException unreadable) {
            return Collections.emptyMap();
        }
    }

    /**
     * Reads the flat key/value catalog.
     *
     * <p>Deliberately tiny: one {@code key: value} per line, {@code #} starts a comment, values may
     * be quoted so a translation can hold a colon, a {@code #}, or leading whitespace. That is all
     * the format this catalog needs, and it keeps the loader free of a YAML dependency and of the
     * reflection and allocation cost a full parser would add on the class-init path.
     */
    static Map<String, String> parse(Reader reader) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        StringBuilder file = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) > 0) file.append(buffer, 0, read);
        for (String rawLine : file.toString().split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (key.isEmpty()) continue;
            entries.put(key, unquote(value));
        }
        return entries;
    }

    private static String unquote(String value) {
        if (value.length() < 2) return value;
        char first = value.charAt(0);
        if (first != '"' && first != '\'') return value;
        if (value.charAt(value.length() - 1) != first) return value;
        String body = value.substring(1, value.length() - 1);
        if (first == '\'') return body;
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                out.append(c);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
