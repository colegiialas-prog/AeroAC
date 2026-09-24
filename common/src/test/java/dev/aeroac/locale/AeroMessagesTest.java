package dev.aeroac.locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every screen depends on: a key becomes a word, a missing key never becomes a blank,
 * and the catalog is a lookup rather than a read.
 */
class AeroMessagesTest {

    @Test
    void theInterfaceShipsInRussian() {
        AeroMessages.preload();
        assertEquals("ru_RU", AeroMessages.DEFAULT_LOCALE);
        assertEquals("ru_RU", AeroMessages.locale());
        assertFalse(AeroMessages.catalog().isEmpty(), "the catalog is bundled with the plugin");
    }

    @Test
    void aKnownKeyResolvesToItsWording() {
        assertEquals("Игроки", AeroMessages.tr("gui.players"));
        assertEquals("НЕТ ДАННЫХ", AeroMessages.tr("admin.no_data"));
        assertEquals("Риск ИИ", AeroMessages.tr("admin.ai_risk"));
    }

    @Test
    void placeholdersAreFilledWithoutLocalesChangingTheShapeOfNumbers() {
        String line = AeroMessages.tr("gui.runtime.detail", 42);
        assertTrue(line.contains("42"), line);
        assertFalse(line.contains("%s"), "a placeholder must be substituted: " + line);
    }

    @Test
    void aMissingKeyResolvesToItselfRatherThanToNothing() {
        assertEquals("no.such.key", AeroMessages.tr("no.such.key"));
        assertFalse(AeroMessages.has("no.such.key"));
        assertTrue(AeroMessages.has("gui.players"));
        assertEquals("", AeroMessages.tr(""));
        assertEquals("", AeroMessages.tr(null));
    }

    @Test
    void aMalformedPlaceholderDoesNotTakeAScreenDown() {
        // One argument against two placeholders: the wording is still readable, nothing throws.
        assertEquals("Поколение снимка: %s", AeroMessages.tr("gui.runtime.detail"));
    }

    @Test
    void theCatalogIsResolvedFromMemory() {
        // The same call twice must not re-read anything: a redraw happens once a second.
        assertSame(AeroMessages.catalog(), AeroMessages.catalog());
        assertEquals(AeroMessages.tr("gui.players"), AeroMessages.tr("gui.players"));
    }

    @Test
    void anUnknownLocaleLeavesTheInterfaceSpeakingTheLanguageItAlreadyHad() {
        String before = AeroMessages.locale();
        assertEquals(before, AeroMessages.setLocale("xx_XX"));
        assertNotNull(AeroMessages.tr("gui.players"));
        assertEquals(before, AeroMessages.locale());
    }

    @Test
    void theEnglishReferenceCatalogCarriesTheSameKeys() {
        var russian = java.util.Map.copyOf(AeroMessages.catalog());
        try {
            assertEquals("en_US", AeroMessages.setLocale("en_US"));
            assertEquals(russian.keySet(), AeroMessages.keys(), "no key may exist in one catalog only");
            assertEquals("Players", AeroMessages.tr("gui.players"), "the reference wording is English");
        } finally {
            AeroMessages.setLocale("ru_RU");
        }
        assertEquals(russian, AeroMessages.catalog());
    }
}
