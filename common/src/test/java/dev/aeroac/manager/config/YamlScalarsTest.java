package dev.aeroac.manager.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped config indents by four spaces; edits must keep its depth, comments and other keys. */
class YamlScalarsTest {
    private static final String FOUR = """
            # top comment
            neural:
                enabled: false
                collection:
                    enabled: false # recording
                inference:
                    # model
                    enabled: false
                    mode: remote
                    local:
                        flash-bundle: "models/flash"
            other: 1
            """;

    @Test void setsNestedValuesAtTheFilesOwnIndentation() {
        String out = YamlScalars.set(FOUR, "neural.inference.mode", "local");
        out = YamlScalars.set(out, "neural.inference.enabled", "true");
        assertTrue(out.contains("\n        mode: local\n"));
        assertTrue(out.contains("        # model\n        enabled: true\n"));
        assertEquals("false", YamlScalars.get(out, "neural.enabled"));
        assertEquals("models/flash", YamlScalars.get(out, "neural.inference.local.flash-bundle"));
        assertTrue(out.contains("# top comment") && out.contains("other: 1"));
    }

    @Test void insertsAMissingKeyInsideItsSectionAtTheRightDepth() {
        String out = YamlScalars.set(FOUR, "neural.training.mode", "local");
        assertTrue(out.contains("\n    training:\n        mode: local\nother: 1"), out);
        assertEquals("local", YamlScalars.get(out, "neural.training.mode"));
    }

    @Test void theRecordingToggleKeepsFourSpaceIndentation() {
        String out = NeuralFlagsFile.apply(FOUR, true);
        assertTrue(out.contains("neural:\n    enabled: true\n"));
        assertTrue(out.contains("        enabled: true # recording"));
        assertTrue(NeuralFlagsFile.applied(out, true));
        assertFalse(out.contains("\n  enabled"), "no key may land at a two-space depth in a four-space file");
    }
}
