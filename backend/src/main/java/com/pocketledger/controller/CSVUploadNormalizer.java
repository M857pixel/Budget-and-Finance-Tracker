package com.pocketledger.controller;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CSVUploadNormalizer {

    private CSVUploadNormalizer() {}

    public static byte[] normalize(MultipartFile file) {
        String filename = file.getOriginalFilename();

        if (filename == null ||
                !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw badRequest("Upload a file with a .csv extension.");
        }

        if (file.isEmpty()) {
            throw badRequest("The uploaded CSV is empty.");
        }

        try {
            String text = decode(file.getBytes());

            // A UTF-8 BOM must not become part of the first header.
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }

            if (text.indexOf('\0') >= 0) {
                throw badRequest("The file contains unsupported binary content.");
            }

            return normalizeText(text);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read the CSV. Upload a valid UTF-8 CSV file.",
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The CSV has an invalid header or CSV structure.",
                    exception
            );
        }
    }

    private static String decode(byte[] contents)
            throws CharacterCodingException {
        // Reject invalid UTF-8 instead of silently replacing characters.
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(contents))
                .toString();
    }

    private static byte[] normalizeText(String text) throws IOException {
        CSVFormat inputFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        List<NormalizedRow> rows = new ArrayList<>();
        boolean debitConvention;

        try (CSVParser parser =
                     inputFormat.parse(new StringReader(text))) {
            Map<String, Integer> headers =
                    normalizeHeaders(parser.getHeaderNames());

            // Alias order determines priority.
            int nameIndex = requiredColumn(
                    headers, "name", "description");
            int amountIndex = requiredColumn(
                    headers, "amount", "debit");
            int dateIndex = requiredColumn(
                    headers, "transaction date", "date", "posting date", "posted date");

            int creditIndex = headers.getOrDefault("credit", -1);
            int categoryIndex = headers.getOrDefault("category", headers.getOrDefault("type", -1));
            int descriptionIndex = headers.getOrDefault("description", -1);

            debitConvention =
                    amountIndex == headers.getOrDefault("debit", -1)
                            || creditIndex >= 0;

            // getRecords() reports malformed CSV through IOException.
            for (CSVRecord record : parser.getRecords()) {
                if (record.stream().allMatch(String::isBlank)) {
                    continue;
                }

                int headerCount = parser.getHeaderNames().size();

                boolean onlyExtraEmptyFields =
                        record.size() > headerCount
                                && java.util.stream.IntStream
                                .range(headerCount, record.size())
                                .allMatch(index -> record.get(index).isBlank());

                if (!record.isConsistent() && !onlyExtraEmptyFields) {
                    throw badRequest(
                            "CSV record " + record.getRecordNumber()
                                    + " does not match the header columns.");
                }

                String amountText = record.get(amountIndex).trim();

                BigDecimal amount = amountText.isEmpty()
                        ? null
                        : parseAmount(amountText, record.getRecordNumber());

                // Ignore credit-only rows before checking name/date.
                if (creditIndex >= 0 &&
                        (amount == null || amount.signum() == 0)) {
                    String creditText = record.get(creditIndex).trim();

                    if (!creditText.isEmpty() &&
                            parseAmount(
                                    creditText,
                                    record.getRecordNumber()
                            ).signum() != 0) {
                        continue;
                    }
                }

                if (amount == null) {
                    throw badRequest(
                            "CSV record " + record.getRecordNumber()
                                    + " has a blank amount.");
                }

                rows.add(new NormalizedRow(
                        record.get(nameIndex).trim(),
                        amount,
                        record.get(dateIndex).trim(),
                        categoryIndex >= 0 ? record.get(categoryIndex).trim() : "",
                        descriptionIndex >= 0 ? record.get(descriptionIndex).trim() : ""
                ));
            }
        }

        if (rows.isEmpty()) {
            throw badRequest(
                    "The CSV contains no transactions to import.");
        }

        long negativeCount = rows.stream()
                .filter(row -> row.amount().signum() < 0)
                .count();

        long positiveCount = rows.stream()
                .filter(row -> row.amount().signum() > 0)
                .count();

        boolean negatePositiveAmounts =
                debitConvention && negativeCount <= positiveCount;

        StringWriter output = new StringWriter();

        CSVFormat outputFormat = CSVFormat.DEFAULT.builder()
                .setHeader("name", "amount", "date", "category", "description")
                .get();

        // CSVPrinter correctly escapes commas, quotes, and line breaks.
        try (CSVPrinter printer = new CSVPrinter(output, outputFormat)) {
            for (NormalizedRow row : rows) {
                BigDecimal amount = row.amount();

                if (negatePositiveAmounts && amount.signum() > 0) {
                    amount = amount.negate();
                }

                printer.printRecord(
                        row.name(),
                        amount.toPlainString(),
                        row.date(),
                        row.category(),
                        row.description()
                );
            }
        }

        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Integer> normalizeHeaders(
            List<String> originalHeaders) {
        Map<String, Integer> headers = new HashMap<>();

        for (int index = 0; index < originalHeaders.size(); index++) {
            String normalized = originalHeaders.get(index)
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[_\\-\\s]+", " ");

            if (normalized.isBlank()) {
                throw badRequest("CSV column names cannot be blank.");
            }

            if (headers.putIfAbsent(normalized, index) != null) {
                throw badRequest(
                        "Duplicate CSV column: " + normalized);
            }
        }

        return headers;
    }

    private static int requiredColumn(
            Map<String, Integer> headers,
            String... aliases) {
        for (String alias : aliases) {
            Integer index = headers.get(alias);

            if (index != null) {
                return index;
            }
        }

        throw badRequest(
                "Missing required CSV column. Expected one of: "
                        + String.join(", ", aliases));
    }

    private static BigDecimal parseAmount(String value, long recordNumber) {
        String number = value.trim();

        boolean parenthesized =
                number.startsWith("(") && number.endsWith(")");

        if (parenthesized) {
            number = number.substring(1, number.length() - 1).trim();
        }

        try {
            BigDecimal amount = new BigDecimal(number);

            return parenthesized
                    ? amount.abs().negate()
                    : amount;
        } catch (NumberFormatException exception) {
            throw badRequest(
                    "CSV record " + recordNumber
                            + " has an invalid amount.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, message);
    }

    private record NormalizedRow(
            String name,
            BigDecimal amount,
            String date,
            String category,
            String description) {}
}