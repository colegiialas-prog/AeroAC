package dev.aeroac.manager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface writes the two recording switches into the operator's own file, so the regression
 * that matters is that everything else in that file survives.
 */
class NeuralFlagsFileTest {

    private static final String OPERATOR_FILE = """
            # Aero AC configuration
            config-version: 2

            neural:
              # master switch
              enabled: false
              collection:
                # nothing is recorded while this is false
                enabled: false
              other: 1

            checks:
              enabled: true
            """;

    @Test
    void writesBothSwitchesAndLeavesEverythingElseAlone() {
        String updated = NeuralFlagsFile.apply(OPERATOR_FILE, true);
        assertNotNull(updated);
        assertTrue(NeuralFlagsFile.applied(updated, true));

        assertTrue(updated.contains("# Aero AC configuration"));
        assertTrue(updated.contains("config-version: 2"));
        assertTrue(updated.contains("  # master switch"));
        assertTrue(updated.contains("    # nothing is recorded while this is false"));
        assertTrue(updated.contains("  enabled: true"));
        assertTrue(updated.contains("    enabled: true"));
        assertTrue(updated.contains("  other: 1"));
        // The unrelated `enabled` of another block must not be touched, in either direction.
        assertTrue(updated.contains("checks:\n  enabled: true"));
        assertFalse(updated.contains("neural:\n  enabled: false"));
    }

    @Test
    void switchingBackOffWritesBothSwitchesOff() {
        String on = NeuralFlagsFile.apply(OPERATOR_FILE, true);
        String off = NeuralFlagsFile.apply(on, false);
        assertTrue(NeuralFlagsFile.applied(off, false));
        assertTrue(off.contains("neural:\n  # master switch\n  enabled: false"));
        // Still only the neural block changed.
        assertTrue(off.contains("checks:\n  enabled: true"));
    }

    @Test
    void aMissingEnabledKeyIsInsertedRatherThanFailing() {
        String withoutMaster = "neural:\n  collection:\n    enabled: false\n";
        String updated = NeuralFlagsFile.apply(withoutMaster, true);
        assertNotNull(updated);
        assertTrue(NeuralFlagsFile.applied(updated, true));
        assertEquals("neural:\n  enabled: true\n  collection:\n    enabled: true\n", updated);
    }

    @Test
    void aMissingCollectionBlockIsCreatedInsideTheNeuralBlock() {
        String withoutCollection = "neural:\n  enabled: false\n\nchecks:\n  enabled: true\n";
        String updated = NeuralFlagsFile.apply(withoutCollection, true);
        assertNotNull(updated);
        assertTrue(NeuralFlagsFile.applied(updated, true));
        assertTrue(updated.contains("  collection:\n    enabled: true"));
        assertTrue(updated.contains("checks:\n  enabled: true"));
    }

    @Test
    void aFileWithoutANeuralBlockGetsOneAppended() {
        String updated = NeuralFlagsFile.apply("checks:\n  enabled: true\n", true);
        assertNotNull(updated);
        assertTrue(NeuralFlagsFile.applied(updated, true));
        assertTrue(updated.contains("checks:\n  enabled: true"));
        assertTrue(updated.endsWith("neural:\n  enabled: true\n  collection:\n    enabled: true"));
    }

    @Test
    void trailingCommentsAndTrailingNewlineArePreserved() {
        String file = "neural:\n  enabled: false # keep me\n  collection:\n    enabled: false # and me\n";
        String updated = NeuralFlagsFile.apply(file, true);
        assertNotNull(updated);
        assertTrue(updated.contains("  enabled: true # keep me"));
        assertTrue(updated.contains("    enabled: true # and me"));
        assertTrue(NeuralFlagsFile.applied(updated, true));
    }

    @Test
    void windowsLineEndingsArePreserved() {
        String file = "neural:\r\n  enabled: false\r\n  collection:\r\n    enabled: false\r\n";
        String updated = NeuralFlagsFile.apply(file, true);
        assertNotNull(updated);
        assertFalse(updated.contains("\n\n"));
        assertTrue(updated.contains("  enabled: true\r\n"));
        assertTrue(updated.contains("    enabled: true\r\n"));
        assertTrue(NeuralFlagsFile.applied(updated, true));
    }

    @Test
    void commentsAboutTheSwitchAreNotMistakenForTheSwitch() {
        String file = "neural:\n  # enabled: true\n  enabled: false\n  collection:\n    enabled: false\n";
        String updated = NeuralFlagsFile.apply(file, true);
        assertNotNull(updated);
        assertTrue(updated.contains("  # enabled: true"));
        assertTrue(NeuralFlagsFile.applied(updated, true));
    }

    @Test
    void appliedDetectsAFileThatIsNotInTheRequestedState() {
        assertFalse(NeuralFlagsFile.applied(OPERATOR_FILE, true));
        assertTrue(NeuralFlagsFile.applied(OPERATOR_FILE, false));
        assertFalse(NeuralFlagsFile.applied("checks:\n  enabled: true\n", false));
    }

    @Test
    void writingIsAtomicAndReplacesTheFileInPlace(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, OPERATOR_FILE);
        NeuralFlagsFile.writeAtomically(file, NeuralFlagsFile.apply(OPERATOR_FILE, true));
        String written = Files.readString(file);
        assertTrue(NeuralFlagsFile.applied(written, true));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count(), "no temporary file may be left behind");
        }
    }
}
