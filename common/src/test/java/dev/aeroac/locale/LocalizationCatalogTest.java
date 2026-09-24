package dev.aeroac.locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog as a deliverable: complete, Russian, and impossible to satisfy by leaving the English
 * in place.
 *
 * <p>Two of these assertions exist because the easy way to make a translation test pass is to make it
 * meaningless: copy the English into the Russian file and assert only that the key exists. So a value
 * that is byte-for-byte its English source fails here unless it is one of the tokens that genuinely
 * has no translation — a unit, an enum code, a brand, a formatting run — and every one of those is
 * named explicitly below rather than licensed by a rule that hides a whole class of misses.
 */
class LocalizationCatalogTest {

    private static final Path LANG = languageDirectory();

    /** Values that are deliberately not Russian: units, codes, brands, formatting runs. */
    private static final Set<String> ALLOWED_IN_ENGLISH = Set.of(
            "admin.s", "admin.m", "admin.h", "admin.ms", "admin.ago",
            "gui.7_lback", "gui.7_lprevious_page", "gui.7_lnext_page", "gui.lclose",
            "gui.training.candidate", "admin.watch", "gui.view",
            "admin.bytes", "gui.window_label",
            // Metric names that are the same acronym in every language a report is read in.
            "gui.compare.metric.roc-auc", "gui.compare.metric.pr-auc");

    /** Wording that must exist and must not be English, because an operator reads it constantly. */
    private static final List<String> MUST_BE_RUSSIAN = List.of(
            "admin.no_data", "admin.ai_risk", "gui.players", "gui.suspicious", "gui.live_monitor",
            "gui.neural_status", "gui.checks", "gui.mitigations", "gui.alerts", "gui.statistics",
            "gui.settings", "gui.training_center", "gui.risk_engine", "gui.inference",
            "gui.training.service_block", "gui.training.start_flash", "gui.training.start_pro",
            "gui.training.cancel_job", "gui.training.confirm_start", "gui.training.job",
            "gui.training.progress", "gui.training.validation_loss", "gui.training.recorded_time",
            "gui.training.quality_warning", "gui.training.attack_windows",
            "gui.confirm.cancel", "gui.runtime.enable", "gui.runtime.state",
            "gui.state.clean", "gui.state.suspicious", "gui.state.mitigated",
            "gui.dataset_label.legit", "gui.dataset_label.cheat", "gui.dataset_label.unlabeled",
            "gui.strength.none", "gui.strength.high", "gui.calibration.uncalibrated",
            "gui.recording_state.failed", "admin.training.status.auditing",
            "admin.training.status.calibrating", "admin.training.status.exporting",
            "admin.training.status.completed", "admin.training.status.cancelled");

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    @BeforeEach void loadCatalog() {
        AeroMessages.preload();
    }

    @Test
    void bothCatalogsCarryExactlyTheSameKeys() throws IOException {
        Map<String, String> russian = read("ru_RU");
        Map<String, String> english = read("en_US");
        assertEquals(english.keySet(), russian.keySet(),
                "a key present in one locale and missing from the other is a translation nobody sees");
        assertTrue(russian.size() > 400, "the catalog covers the whole interface, not a corner of it");
    }

    @Test
    void everyValueIsPresent() throws IOException {
        for (Map.Entry<String, String> entry : read("ru_RU").entrySet()) {
            assertFalse(entry.getValue().isBlank(), "empty translation for " + entry.getKey());
        }
    }

    @Test
    void noValueSimplyRepeatsItsKey() throws IOException {
        for (Map.Entry<String, String> entry : read("ru_RU").entrySet()) {
            assertFalse(entry.getValue().equals(entry.getKey()),
                    "the interface would print the key itself for " + entry.getKey());
        }
    }

    @Test
    void theRussianCatalogIsNotEnglishWithRussianKeys() throws IOException {
        Map<String, String> russian = read("ru_RU");
        Map<String, String> english = read("en_US");
        List<String> untranslated = new ArrayList<>();
        for (Map.Entry<String, String> entry : russian.entrySet()) {
            if (ALLOWED_IN_ENGLISH.contains(entry.getKey())) continue;
            if (entry.getValue().equals(english.get(entry.getKey()))) untranslated.add(entry.getKey());
        }
        assertTrue(untranslated.isEmpty(),
                "these keys are English on both sides, which is a translation that never happened: "
                        + untranslated);
    }

    @Test
    void theWordsAnOperatorReadsMostAreRussian() throws IOException {
        Map<String, String> russian = read("ru_RU");
        for (String key : MUST_BE_RUSSIAN) {
            String value = russian.get(key);
            assertNotNull(value, "missing key " + key);
            assertTrue(CYRILLIC.matcher(value).find(), key + " is not Russian: " + value);
        }
    }

    @Test
    void theInterfaceVocabularyIsCompleteForEveryInternalCode() throws IOException {
        Map<String, String> russian = read("ru_RU");
        for (String state : List.of("clean", "watch", "suspicious", "mitigated", "confirmed")) {
            assertTrue(russian.containsKey("gui.state." + state), "state " + state);
        }
        for (String label : List.of("legit", "cheat", "unlabeled")) {
            assertTrue(russian.containsKey("gui.dataset_label." + label), "label " + label);
        }
        for (String strength : List.of("none", "very_low", "low", "medium", "high", "unknown")) {
            assertTrue(russian.containsKey("gui.strength." + strength), "strength " + strength);
        }
        for (String mode : List.of("off", "all", "suspicious", "auto")) {
            assertTrue(russian.containsKey("gui.mode." + mode), "view mode " + mode);
        }
        for (String status : List.of("not_configured", "idle", "queued", "preparing", "auditing",
                "training", "calibrating", "evaluating", "exporting", "trained", "evaluated",
                "completed", "candidate", "cancelled", "failed", "unknown")) {
            assertTrue(russian.containsKey("admin.training.status." + status), "job status " + status);
        }
        for (String state : List.of("recording", "closing", "failed")) {
            assertTrue(russian.containsKey("gui.recording_state." + state), "recording state " + state);
        }
    }

    @Test
    void theTrainingCentreHasWordingForEveryStepItOffers() throws IOException {
        Map<String, String> russian = read("ru_RU");
        for (String key : List.of("gui.training.service_state", "gui.training.service_connected",
                "gui.training.service_not_connected", "gui.training.launcher_unavailable",
                "gui.training.job_already_running", "gui.training.preset", "gui.training.dataset",
                "gui.training.confirm_title", "gui.training.confirm_cancel",
                "gui.training.nothing_here_promotes_a_model", "gui.runtime.unavailable",
                "gui.runtime.confirm_enable", "gui.runtime.confirm_disable", "admin.bytes")) {
            assertNotNull(russian.get(key), "missing key " + key);
        }
    }

    /**
     * Placeholders are {@code %s}, because {@code AeroMessages.tr(key, args)} formats with
     * {@code String.format}.
     *
     * <p>A {@code {0}} is silently left in place by that formatter, which is how an announcement
     * once went out reading "the anticheat would ban {0}" with no name in it. It rendered, nothing
     * threw, and only a test that read the text back noticed.
     */
    @Test
    void placeholdersMatchTheFormatterThatFillsThem() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String locale : List.of("ru_RU", "en_US")) {
            for (Map.Entry<String, String> entry : read(locale).entrySet()) {
                if (entry.getValue().matches("(?s).*\\{[0-9]+}.*")) wrong.add(locale + " " + entry.getKey());
            }
        }
        assertTrue(wrong.isEmpty(), "MessageFormat placeholders in a String.format catalog: " + wrong);
    }

    /**
     * A translation takes exactly as many arguments as the original.
     *
     * <p>One placeholder short and the formatter throws, the catch shows the raw pattern, and the
     * operator reads "%s" where a player's name should be.
     */
    @Test
    void everyTranslationTakesTheSameArguments() throws IOException {
        Map<String, String> russian = read("ru_RU");
        Map<String, String> english = read("en_US");
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, String> entry : russian.entrySet()) {
            String other = english.get(entry.getKey());
            if (other == null) continue;
            if (specifiers(entry.getValue()) != specifiers(other)) mismatched.add(entry.getKey());
        }
        assertTrue(mismatched.isEmpty(), "placeholder count differs between languages: " + mismatched);
    }

    /**
     * A value that is its own key is a translation nobody wrote.
     *
     * <p>Fifty-eight of these once sat in the English catalog: the training status, the confirm
     * screens, the runtime switch. The Russian side was complete, so nothing looked wrong until
     * somebody switched language and read "gui.runtime.enable" on a button.
     */
    @Test
    void noValueIsJustItsOwnKey() throws IOException {
        List<String> stubs = new ArrayList<>();
        for (String locale : List.of("ru_RU", "en_US")) {
            for (Map.Entry<String, String> entry : read(locale).entrySet()) {
                if (entry.getValue().equals(entry.getKey())) stubs.add(locale + " " + entry.getKey());
            }
        }
        assertTrue(stubs.isEmpty(), "untranslated stubs: " + stubs);
    }

    private static int specifiers(String value) {
        int count = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%[sd]").matcher(value);
        while (matcher.find()) count++;
        return count;
    }

    private static Map<String, String> read(String locale) throws IOException {
        Path file = LANG.resolve(locale + ".yml");
        assertTrue(Files.isRegularFile(file), "catalog missing: " + file);
        Map<String, String> entries = new LinkedHashMap<>();
        for (String raw : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            entries.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        return entries;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path languageDirectory() {
        Path moduleRelative = Path.of("src", "main", "resources", "lang");
        if (Files.isDirectory(moduleRelative)) return moduleRelative;
        return Path.of("common", "src", "main", "resources", "lang");
    }
}
