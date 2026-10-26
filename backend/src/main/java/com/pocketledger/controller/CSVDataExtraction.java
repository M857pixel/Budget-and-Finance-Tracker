/*
  Imports transactions from a CSV file uploaded through FileUploadController.

  The upload is kept in the user's session. After logging in and uploading a
  file to POST /api/import, call POST /api/import/transactions with that same
  session to save its rows.

  The CSV must have name, amount, and date columns;

  Dates may use YYYY-MM-DD, M/D/YYYY, or M-D-YYYY; month and day can be one or two digits.

  The logged-in account owns every imported transaction.


  Command Line Steps:

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

    $login = Invoke-RestMethod -Method Post `
      -Uri "http://localhost:8080/api/login" `
      -ContentType "application/json" `
      -Body '{"email":"YOUR_EMAIL","password":"YOUR_PASSWORD"}' `
      -WebSession $session

    $cookieHeader = ($session.Cookies.GetCookies([uri]"http://localhost:8080") |
      ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; "

    $csvPath = "C:\SE_Project\YOUR_FOLDER\your-file.csv"

    curl.exe -i -H "Cookie: $cookieHeader" `
      -F "file=@$csvPath" `
      "http://localhost:8080/api/import"

    curl.exe -i -H "Cookie: $cookieHeader" `
      -X POST `
      "http://localhost:8080/api/import/transactions"
 */
package com.pocketledger.controller;

import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class CSVDataExtraction {

    static final Set<String> REQUIRED_COLUMNS = Set.of("name", "amount", "date");
    static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("M-d-uuuu").withResolverStyle(ResolverStyle.STRICT)
    );

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public CSVDataExtraction(UserRepository userRepository, TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    /*
      Imports the CSV bytes placed in the session by FileUploadController's POST /api/import.
      The authenticated userId stored by AuthController determines ownership; the CSV has no user ID.
     */
    @PostMapping("/import/transactions")
    @Transactional
    public ImportResponse importTransactions(HttpSession session) {
        // The login endpoint stores this ID in the server session.
        Object authenticatedUserId = session.getAttribute("userId");
        if (!(authenticatedUserId instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in before importing transactions.\n");
        }

        // FileUploadController puts the uploaded bytes in this same session.
        Object upload = session.getAttribute("uploadedFile");
        if (!(upload instanceof FileUploadController.UploadedFile uploadedFile)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Upload a CSV file first using POST /api/import with form field 'file'.\n");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Logged-in user was not found.\n"));

        List<Transaction> transactions;
        // Decode the saved upload as UTF-8, then parse every row before writing anything.
        try (StringReader reader = new StringReader(new String(uploadedFile.contents(), java.nio.charset.StandardCharsets.UTF_8))) {
            transactions = new CSVExtractor().extract(reader, user);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the uploaded CSV file.\n", exception);
        }

        transactionRepository.saveAll(transactions);
        // Avoid importing the same upload again by accident.
        session.removeAttribute("uploadedFile");
        return new ImportResponse(transactions.size());
    }

    public record ImportResponse(int importedCount) {}
}

// Turns CSV rows into transaction entities for the user who is signed in.
class CSVExtractor {

    List<Transaction> extract(java.io.Reader reader, User user) throws IOException {
        // Use the first CSV row as column names so fields can be read by name.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        try (CSVParser parser = format.parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            if (headers == null || !headers.keySet().containsAll(CSVDataExtraction.REQUIRED_COLUMNS)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV header must include columns: name, amount, date.\n");
            }

            List<Transaction> transactions = new ArrayList<>();
            for (CSVRecord row : parser) {
                // Ignore rows with no values, but report incomplete or malformed rows below.
                if (row.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (!row.isConsistent()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " does not match the header columns.\n");
                }

                String name = row.get("name").trim();
                if (name.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " has a blank name.\n");
                }

                // Amount and date errors name the CSV row to make corrections easier.
                BigDecimal amount;
                try {
                    amount = new BigDecimal(row.get("amount").trim());
                } catch (NumberFormatException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " has an invalid amount.\n", exception);
                }

                LocalDate date = parseDate(row.get("date").trim(), row.getRecordNumber());
                // The CSV supplies transaction details; the signed-in user supplies ownership.
                transactions.add(new Transaction(user, name, null, amount, date, null));
            }
            return transactions;
        }
    }

    private LocalDate parseDate(String value, long rowNumber) {
        // Try each accepted format and only reject the value if none match.
        for (DateTimeFormatter formatter : CSVDataExtraction.DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported date format.
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "CSV row " + rowNumber + " has an invalid date; use YYYY-MM-DD, M/D/YYYY, or M-D-YYYY (month and day may be one or two digits).\n");
    }
}
