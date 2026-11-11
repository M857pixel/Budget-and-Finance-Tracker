/*
    Service for currency exchange rate lookups and conversions using Frankfurter API.
    Integrates with the AI assistant to handle currency conversion queries.

    Usage in AI context:
    - "Convert 50 USD to EUR"
    - "What's the exchange rate from USD to JPY?"
    - "How much iis

    Supported AI flows:
    CONVERT_CURRENCY - Returns converted amount with formatting
*/

package com.pocketledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Service
public class CurrencyConversionService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyConversionService.class);
    private static final String API_BASE = "https://api.frankfurter.dev/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CurrencyConversionService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /** Fetches the current exchange rate between two currencies.*/
    public BigDecimal getExchangeRate(String fromCurrency, String toCurrency) throws CurrencyConversionException {
        validateCurrencyCode(fromCurrency);
        validateCurrencyCode(toCurrency);

        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/rate/" + fromCurrency.toUpperCase() + "/" + toCurrency.toUpperCase()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new CurrencyConversionException(
                        "Failed to fetch exchange rate: HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode rateNode = root.get("rate");

            if (rateNode == null || !rateNode.isNumber()) {
                throw new CurrencyConversionException(
                        "Invalid response from Frankfurter API: " + response.body());
            }

            return rateNode.decimalValue();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CurrencyConversionException("Interrupted while fetching exchange rate", e);
        } catch (Exception e) {
            throw new CurrencyConversionException("Error fetching exchange rate", e);
        }
    }

    /** Converts an amount from one currency to another.*/
    public BigDecimal convertCurrency(BigDecimal amount, String fromCurrency, String toCurrency)
            throws CurrencyConversionException {

        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }

        BigDecimal rate = getExchangeRate(fromCurrency, toCurrency);
        return amount.multiply(rate);
    }

    /** Returns a formatted human-readable response for currency conversion.*/
    public String formatConversion(BigDecimal amount, String fromCurrency, String toCurrency)
            throws CurrencyConversionException {

        BigDecimal rate = getExchangeRate(fromCurrency, toCurrency);
        BigDecimal converted = amount.multiply(rate);

        return String.format(Locale.US,
                "%.2f %s = %.2f %s (1 %s = %.4f %s)", amount, fromCurrency.toUpperCase(Locale.ROOT), converted, toCurrency.toUpperCase(Locale.ROOT),
                fromCurrency.toUpperCase(Locale.ROOT), rate, toCurrency.toUpperCase(Locale.ROOT));
    }


    /** Validates that a currency code is a valid three-letter ISO code.*/
    private void validateCurrencyCode(String code) {
        if (code == null || !isThreeLetterCode(code)) {
            throw new IllegalArgumentException(
                    "Currency code must be a valid three-letter code (e.g., USD, EUR, GBP)");
        }
    }

    /** Checks if a string is a valid three-letter alphabetic code. */
    private boolean isThreeLetterCode(String value) {
        if (value.length() != 3) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (!Character.isLetter(c)) {
                return false;
            }
        }
        return true;
    }

    /** Custom exception for currency conversion failures.*/
    public static class CurrencyConversionException extends RuntimeException {
        public CurrencyConversionException(String message) {
            super(message);
        }

        public CurrencyConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}