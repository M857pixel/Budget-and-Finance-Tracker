package com.pocketledger.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AiActionParametersTest {

    // ---------- amount(String) ----------

    @Test
    void amountParsesPlainDecimalString() {
        assertEquals(new BigDecimal("-42.50"), AiActionParameters.amount("-42.50"));
        assertEquals(new BigDecimal("1500.00"), AiActionParameters.amount("1500.00"));
    }

    @Test
    void amountStripsCurrencySymbolsAndSeparators() {
        assertEquals(new BigDecimal("1234.56"), AiActionParameters.amount("$1,234.56"));
        assertEquals(new BigDecimal("42.50"), AiActionParameters.amount("€42.50"));
        assertEquals(new BigDecimal("7"), AiActionParameters.amount("£ 7 "));
    }

    @Test
    void amountIgnoresSurroundingAnnotationCharacters() {
        assertEquals(new BigDecimal("10.00"), AiActionParameters.amount("~10.00~"));
        assertEquals(new BigDecimal("-5"), AiActionParameters.amount("(~-5)"));
    }

    @Test
    void amountKeepsLeadingMinusThroughCleanup() {
        assertEquals(new BigDecimal("-99.99"), AiActionParameters.amount(" -$99.99 "));
    }

    @Test
    void amountReturnsNullForNullInput() {
        assertNull(AiActionParameters.amount(null));
    }

    @Test
    void amountReturnsNullForBlankInput() {
        assertNull(AiActionParameters.amount(""));
        assertNull(AiActionParameters.amount("   "));
    }

    @Test
    void amountReturnsNullWhenNoDigitsRemain() {
        assertNull(AiActionParameters.amount("$"));
        assertNull(AiActionParameters.amount("abc"));
    }

    @Test
    void amountReturnsNullForMalformedResultingNumber() {
        // stripping leaves "2.5.3" which BigDecimal cannot parse
        assertNull(AiActionParameters.amount("2.5.3"));
        assertNull(AiActionParameters.amount("--5"));
    }

    @Test
    void amountHandlesIntegerAndHighPrecisionValues() {
        assertEquals(new BigDecimal("100"), AiActionParameters.amount("100"));
        assertEquals(new BigDecimal("0.005"), AiActionParameters.amount("0.005"));
    }

    // ---------- date(String, LocalDate) ----------

    @Test
    void dateParsesIsoFormat() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(LocalDate.of(2026, 11, 16),
                AiActionParameters.date("2026-11-16", fallback));
    }

    @Test
    void dateTrimsWhitespaceBeforeParsing() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(LocalDate.of(2026, 3, 3),
                AiActionParameters.date("  2026-03-03  ", fallback));
    }

    @Test
    void dateReturnsFallbackForNull() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(fallback, AiActionParameters.date(null, fallback));
    }

    @Test
    void dateReturnsFallbackForBlank() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(fallback, AiActionParameters.date("", fallback));
        assertEquals(fallback, AiActionParameters.date("  ", fallback));
    }

    @Test
    void dateReturnsFallbackForMalformedInput() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(fallback, AiActionParameters.date("16/11/2026", fallback));
        assertEquals(fallback, AiActionParameters.date("tomorrow", fallback));
        assertEquals(fallback, AiActionParameters.date("2026-13-45", fallback));
    }

    // ---------- isBlank(String) ----------

    @Test
    void isBlankIdentifiesNullEmptyAndWhitespace() {
        assertTrue(AiActionParameters.isBlank(null));
        assertTrue(AiActionParameters.isBlank(""));
        assertTrue(AiActionParameters.isBlank("   "));
        assertTrue(AiActionParameters.isBlank("\t\n"));
    }

    @Test
    void isBlankIsFalseForMeaningfulStrings() {
        assertFalse(AiActionParameters.isBlank("coffee"));
        assertFalse(AiActionParameters.isBlank(" x "));
        assertFalse(AiActionParameters.isBlank("0"));
    }
}