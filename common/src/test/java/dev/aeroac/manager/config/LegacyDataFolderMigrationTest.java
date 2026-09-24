package dev.aeroac.manager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upgraded server must keep its configuration, datasets and bundles without ever having two
 * active folders, and without the plugin silently merging two different configs.
 */
class LegacyDataFolderMigrationTest {

    private static final String LEGACY_CONFIG = "config-version: 2\nneural:\n  enabled: true\n";
    private static final String OTHER_CONFIG = "config-version: 2\nneural:\n  enabled: false\n";

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void copiesTheLegacyFolderIntoTheActiveOneExactlyOnce(@TempDir Path plugins) throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        write(plugins.resolve("GrimAC/config.yml"), LEGACY_CONFIG);
        write(plugins.resolve("GrimAC/messages.yml"), "prefix: '&c'");
        write(plugins.resolve("GrimAC/config.yml"), LEGACY_CONFIG);
        Files.createDirectories(plugins.resolve("GrimAC/datasets"));
        Files.write(plugins.resolve("GrimAC/datasets/pseudonym.key"), key);
        write(plugins.resolve("GrimAC/datasets/raw/session-a.jsonl"), "{\"tick\":1}\n");
        write(plugins.resolve("GrimAC/bundles/flash.onnx"), "model");
        write(plugins.resolve("GrimAC/logs/latest.log"), "not ours");

        LegacyDataFolderMigration.Result result = LegacyDataFolderMigration.migrate(plugins);

        assertEquals(LegacyDataFolderMigration.Status.MIGRATED, result.status());
        assertTrue(result.ok());
        assertTrue(result.migratedNow());
        assertTrue(result.conflicts().isEmpty());

        assertEquals(LEGACY_CONFIG, Files.readString(plugins.resolve("AeroAC/config.yml")));
        assertEquals("prefix: '&c'", Files.readString(plugins.resolve("AeroAC/messages.yml")));
        assertEquals(32, Files.readAllBytes(plugins.resolve("AeroAC/datasets/pseudonym.key")).length);
        assertTrue(Files.exists(plugins.resolve("AeroAC/datasets/raw/session-a.jsonl")));
        assertTrue(Files.exists(plugins.resolve("AeroAC/bundles/flash.onnx")));
        assertFalse(Files.exists(plugins.resolve("AeroAC/logs/latest.log")),
                "only the plugin's own entries are carried over");

        // Copy-only: the old folder is untouched, so restoring the previous jar still works.
        assertEquals(LEGACY_CONFIG, Files.readString(plugins.resolve("GrimAC/config.yml")));
        assertTrue(Files.exists(plugins.resolve("GrimAC/datasets/raw/session-a.jsonl")));
        assertTrue(Files.exists(plugins.resolve("AeroAC").resolve(LegacyDataFolderMigration.MARKER_FILE)));

        LegacyDataFolderMigration.Result second = LegacyDataFolderMigration.migrate(plugins);
        assertEquals(LegacyDataFolderMigration.Status.ALREADY_MIGRATED, second.status());
        assertTrue(second.copied().isEmpty());

        // Even a legacy folder that changed afterwards cannot re-run the migration.
        write(plugins.resolve("GrimAC/config.yml"), "config-version: 2\nneural:\n  enabled: false\n");
        assertEquals(LegacyDataFolderMigration.Status.ALREADY_MIGRATED,
                LegacyDataFolderMigration.migrate(plugins).status());
        assertEquals(LEGACY_CONFIG, Files.readString(plugins.resolve("AeroAC/config.yml")));
    }

    @Test
    void aDifferentFileInBothFoldersStopsEverythingAndTouchesNothing(@TempDir Path plugins) throws Exception {
        write(plugins.resolve("AeroAC/config.yml"), OTHER_CONFIG);
        write(plugins.resolve("GrimAC/config.yml"), LEGACY_CONFIG);
        write(plugins.resolve("GrimAC/messages.yml"), "prefix: '&c'");
        write(plugins.resolve("GrimAC/datasets/raw/session-a.jsonl"), "{}\n");

        LegacyDataFolderMigration.Result result = LegacyDataFolderMigration.migrate(plugins);

        assertEquals(LegacyDataFolderMigration.Status.CONFLICT, result.status());
        assertFalse(result.ok());
        assertTrue(result.conflicts().contains("config.yml"));
        assertTrue(result.copied().isEmpty(), "fail closed: not a single file is copied");
        // The active configuration the operator already has is never overwritten...
        assertEquals(OTHER_CONFIG, Files.readString(plugins.resolve("AeroAC/config.yml")));
        // ...and nothing else is smuggled in either, so the state is exactly what they left.
        assertFalse(Files.exists(plugins.resolve("AeroAC/messages.yml")));
        assertFalse(Files.exists(plugins.resolve("AeroAC/datasets/raw/session-a.jsonl")));
        assertFalse(Files.exists(plugins.resolve("AeroAC").resolve(LegacyDataFolderMigration.MARKER_FILE)),
                "no marker, so the operator can resolve the conflict and restart");
        // The old folder stays untouched for a manual merge.
        assertEquals(LEGACY_CONFIG, Files.readString(plugins.resolve("GrimAC/config.yml")));
    }

    @Test
    void aPseudonymKeyThatDiffersIsAConflictToo(@TempDir Path plugins) throws Exception {
        write(plugins.resolve("AeroAC/datasets/pseudonym.key"), "a".repeat(32));
        write(plugins.resolve("GrimAC/datasets/pseudonym.key"), "b".repeat(32));

        LegacyDataFolderMigration.Result result = LegacyDataFolderMigration.migrate(plugins);

        assertEquals(LegacyDataFolderMigration.Status.CONFLICT, result.status());
        assertTrue(result.conflicts().contains("datasets/pseudonym.key"));
        assertEquals("a".repeat(32), Files.readString(plugins.resolve("AeroAC/datasets/pseudonym.key")),
                "the pseudonyms of an existing dataset set are never replaced");
    }

    @Test
    void identicalFilesAreNotConflictsAndStopNothing(@TempDir Path plugins) throws Exception {
        write(plugins.resolve("AeroAC/config.yml"), LEGACY_CONFIG);
        write(plugins.resolve("GrimAC/config.yml"), LEGACY_CONFIG);
        write(plugins.resolve("GrimAC/datasets/raw/session-b.jsonl"), "{}\n");

        LegacyDataFolderMigration.Result result = LegacyDataFolderMigration.migrate(plugins);

        assertEquals(LegacyDataFolderMigration.Status.MIGRATED, result.status());
        assertEquals(LEGACY_CONFIG, Files.readString(plugins.resolve("AeroAC/config.yml")));
        assertTrue(Files.exists(plugins.resolve("AeroAC/datasets/raw/session-b.jsonl")));
        assertFalse(result.copied().contains("config.yml"),
                "an already-identical file is skipped, not copied over");
    }

    @Test
    void aFreshInstallHasNothingToDo(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("AeroAC"));
        LegacyDataFolderMigration.Result result = LegacyDataFolderMigration.migrate(plugins);
        assertEquals(LegacyDataFolderMigration.Status.NOTHING_TO_DO, result.status());
        assertTrue(result.ok());
        assertEquals(plugins.resolve("AeroAC"), result.activeFolder());
        assertEquals(plugins.resolve("GrimAC"), result.legacyFolder());
    }

    @Test
    void everyOperatorMessageIsRussian(@TempDir Path plugins) throws Exception {
        write(plugins.resolve("GrimAC/config.yml"), LEGACY_CONFIG);
        LegacyDataFolderMigration.Result migrated = LegacyDataFolderMigration.migrate(plugins);
        assertNotNull(migrated.summary());
        assertTrue(migrated.summary().matches(".*[А-Яа-я].*"), migrated.summary());

        write(plugins.resolve("GrimAC/datasets/raw/x.jsonl"), "{}\n");
        // Rewriting the same path with different content in both folders is the conflict case.
        write(plugins.resolve("AeroAC/datasets/raw/x.jsonl"), "different\n");
        Files.deleteIfExists(plugins.resolve("AeroAC").resolve(LegacyDataFolderMigration.MARKER_FILE));
        LegacyDataFolderMigration.Result conflict = LegacyDataFolderMigration.migrate(plugins);
        assertEquals(LegacyDataFolderMigration.Status.CONFLICT, conflict.status());
        assertTrue(conflict.summary().matches(".*[А-Яа-я].*"), conflict.summary());
    }

    @Test
    void theOwnedEntriesAreTheOnesAnInstallationNeeds() {
        assertTrue(LegacyDataFolderMigration.ownedEntries().contains("config.yml"));
        assertTrue(LegacyDataFolderMigration.ownedEntries().contains("datasets"));
        assertTrue(LegacyDataFolderMigration.ownedEntries().contains("bundles"));
        assertTrue(LegacyDataFolderMigration.ownedEntries().contains("databases"));
        assertEquals("AeroAC", LegacyDataFolderMigration.ACTIVE_FOLDER);
        assertEquals("GrimAC", LegacyDataFolderMigration.LEGACY_FOLDER);
    }
}
