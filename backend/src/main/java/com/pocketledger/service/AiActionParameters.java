/**
 * Utility class providing parsing and validation helpers for AI-supplied parameters.
 *
 * Provided Utilities:
 * 1. amount(String) → BigDecimal
 *    - Strips everything except digits, '.', and '-' (removes currency symbols,
 *      thousand separators, stray whitespace or annotation characters)
 *    - Returns null when input is absent, blank, or unparseable — callers distinguish
 *      "missing" from "invalid" as needed
 * 2. date(String, LocalDate) → LocalDate
 *    - YYYY-MM-DD
 *    - Returns the caller-supplied fallback when input is absent or malformed
 * 3. isBlank(String) → boolean
 *    - Null-safe emptiness check used throughout validation of required parameters
 */


package com.pocketledger.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class AiActionParameters {
    private AiActionParameters() {}

    /** Extracts a numeric amount from a string, returning null if unparseable. */
    public static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) return null;

        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                cleaned.append(c);
            }
        }

        try {
            return new BigDecimal(cleaned.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }



    /** Parses a date string, returning the fallback if absent or malformed. */
    public static LocalDate date(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Checks if a string is null, empty, or whitespace-only. */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}