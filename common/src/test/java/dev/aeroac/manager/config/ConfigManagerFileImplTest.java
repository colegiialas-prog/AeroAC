package dev.aeroac.manager.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerFileImplTest {

    @Test
    void addsMissingLightningCombatPunishmentGroups() {
        String input = """
                Punishments:
                  Reach:
                    remove-violations-after: 450
                    checks:
                      - "Reach"
                      - "Hitboxes"
                    commands:
                      - "1:1 [log]"
                """;

        String output = ConfigManagerFileImpl.ensureLightningCombatPunishmentGroups(input);

        assertTrue(output.contains("  WallHit:\n"));
        assertTrue(output.contains("      - \"WallHit\"\n"));
        assertTrue(output.contains("  EntityPierce:\n"));
        assertTrue(output.contains("      - \"EntityPierce\"\n"));
        assertTrue(output.contains("  Reach:\n"));
    }

    @Test
    void doesNotDuplicateExistingGroups() {
        String input = """
                Punishments:
                  WallHit:
                    remove-violations-after: 1
                    checks:
                      - "WallHit"
                    commands:
                      - "1:1 [alert]"
                  EntityPierce:
                    remove-violations-after: 1
                    checks:
                      - "EntityPierce"
                    commands:
                      - "1:1 [alert]"
                """;

        String output = ConfigManagerFileImpl.ensureLightningCombatPunishmentGroups(input);

        assertEquals(input, output);
    }

    @Test
    void ignoresNonPunishmentYaml() {
        String input = """
                alerts:
                  print-to-console: true
                """;

        assertEquals(input, ConfigManagerFileImpl.ensureLightningCombatPunishmentGroups(input));
    }

    @Test
    void appendsTheAirStuckGroupToAnOlderPunishmentsFile() {
        String input = """
                Punishments:
                  Simulation:
                    remove-violations-after: 300
                    checks:
                      - "Simulation"
                      - "NoFall"
                    commands:
                      - "100:40 [alert]"
                """;

        String output = ConfigManagerFileImpl.ensureAeroMovementPunishmentGroups(input);

        assertTrue(output.startsWith(input));
        assertTrue(output.contains("  AirStuck:\n"));
        assertTrue(output.contains("      - \"AirStuck\"\n"));
        assertTrue(output.contains("      - \"3:5 [alert]\"\n"));
        assertEquals(output, ConfigManagerFileImpl.ensureAeroMovementPunishmentGroups(output), "idempotent");
    }

    @Test
    void leavesAirStuckAloneWhereverTheOperatorPutIt() {
        for (String entry : new String[]{"      - \"AirStuck\"\n", "      - \"!AirStuck\"\n", "      - airstuck\n"}) {
            String input = "Punishments:\n  Movement:\n    checks:\n" + entry + "    commands:\n      - \"1:1 [alert]\"\n";
            assertEquals(input, ConfigManagerFileImpl.ensureAeroMovementPunishmentGroups(input), entry);
        }
        String notPunishments = "alerts:\n  print-to-console: true\n";
        assertEquals(notPunishments, ConfigManagerFileImpl.ensureAeroMovementPunishmentGroups(notPunishments));
    }
}
