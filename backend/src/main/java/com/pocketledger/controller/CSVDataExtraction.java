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

    // Defines the three columns that every imported CSV file must contain.
    static final Set<String> REQUIRED_COLUMNS = Set.of("name", "amount", "date");

    // Defines all date formats that the CSV importer accepts.
    // ISO_LOCAL_DATE accepts dates such as 2026-11-08.
    // The other two allow dates such as 11/8/2026 or 11-8-2026.
    static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("M-d-uuuu").withResolverStyle(ResolverStyle.STRICT)
    );

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    // Constructor injection:
    // Spring automatically provides both repository objects when this controller is created.
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
        // Retrieve the user ID that was stored in the session during login.
        Object authenticatedUserId = session.getAttribute("userId");

        // Verify that the session contains a valid Long user ID.
        // If it does not, the request is rejected because the user is not authenticated.
        if (!(authenticatedUserId instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in before importing transactions.\n");
        }

        // Retrieve the previously uploaded CSV file from the same user session.
        Object upload = session.getAttribute("uploadedFile");

        // Verify that a file has already been uploaded through POST /api/import.
        // If not, return a 400 Bad Request.
        if (!(upload instanceof FileUploadController.UploadedFile uploadedFile)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Upload a CSV file first using POST /api/import with form field 'file'.\n");
        }

        // Look up the logged-in user's database record using the user ID from the session.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Logged-in user was not found.\n"));

        // This list will contain all Transaction objects created from the CSV rows.
        List<Transaction> transactions;

        // Convert the uploaded file bytes into UTF-8 text,
        // wrap that text in a StringReader,
        // and send it to CSVExtractor for parsing.
        try (StringReader reader = new StringReader(new String(uploadedFile.contents(), java.nio.charset.StandardCharsets.UTF_8))) {
            // Parse the CSV and convert every valid row into a Transaction object.
            // The logged-in User object is passed in so every transaction belongs to that user.
            transactions = new CSVExtractor().extract(reader, user);
        } catch (IOException exception) {
            // If the uploaded CSV cannot be read,
            // return a 400 Bad Request to the client.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the uploaded CSV file.\n", exception);
        }

        // Save every parsed Transaction object to the database.
        transactionRepository.saveAll(transactions);
        // Remove the uploaded file from the session after a successful import.
        // This prevents the same file from accidentally being imported twice.
        session.removeAttribute("uploadedFile");
        // Return the number of transactions that were successfully imported.
        return new ImportResponse(transactions.size());
    }

    // Small response object returned to the frontend.
    // Example response:
    // {"importedCount": 10}
    public record ImportResponse(int importedCount) {}
}

// Helper class responsible specifically for parsing CSV data
// and turning CSV rows into Transaction entities.
class CSVExtractor {

    // Reads CSV content from a Reader and converts each row
    // into a Transaction belonging to the provided User.
    List<Transaction> extract(java.io.Reader reader, User user) throws IOException {

        // Configure Apache Commons CSV.
        // setHeader(): Uses the first row of the CSV file as column names.
        // setSkipHeaderRecord(true): Prevents the header itself from being treated as transaction data.
        // setIgnoreEmptyLines(true): Skips completely empty lines.
        // setTrim(true): Removes unnecessary spaces around CSV values.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        // Create the parser using the configured CSV format.
        // try-with-resources automatically closes the parser when parsing is complete.
        try (CSVParser parser = format.parse(reader)) {

            // Get a map of CSV header names to their column positions.
            // Example: name   -> 0, amount -> 1, date   -> 2
            Map<String, Integer> headers = parser.getHeaderMap();
            // Verify that the CSV contains all required columns. name, amount, date
            if (headers == null || !headers.keySet().containsAll(CSVDataExtraction.REQUIRED_COLUMNS)) {
                // Reject the entire import if required headers are missing.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV header must include columns: name, amount, date.\n");
            }

            // Create a list that will hold every Transaction object
            // successfully parsed from the CSV.
            List<Transaction> transactions = new ArrayList<>();

            // Loop through every data row in the CSV file.
            for (CSVRecord row : parser) {

                // Check whether every value in the current row is blank.
                // If the row contains no data, skip it.
                if (row.stream().allMatch(String::isBlank)) {
                    continue;
                }

                // Verify that the row has the same number of values as the header.
                // For example, if the CSV header has: name,amount,date
                // but a row only contains: Walmart,45.00
                // the row is inconsistent and should be rejected.
                if (!row.isConsistent()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " does not match the header columns.\n");
                }

                // Retrieve the transaction name using the "name" CSV column.
                // trim() removes leading or trailing spaces.
                String name = row.get("name").trim();
                // Transaction name is required.
                // Reject the row if the name field is empty.
                if (name.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " has a blank name.\n");
                }

                // Declare the transaction amount.
                BigDecimal amount;
                // Attempt to convert the CSV amount string into BigDecimal.
                // Examples accepted by BigDecimal include: 45.25, +45.25, -45.25
                try {
                    amount = new BigDecimal(row.get("amount").trim());
                } catch (NumberFormatException exception) {
                    // If the amount contains invalid characters or cannot be converted,
                    // reject the import and identify which CSV row caused the problem.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV row " + row.getRecordNumber() + " has an invalid amount.\n", exception);
                }

                // Retrieve and parse the date from the CSV.
                // parseDate() attempts each supported date format and
                // returns the result as a LocalDate.
                LocalDate date = parseDate(row.get("date").trim(), row.getRecordNumber());
                // The CSV supplies transaction details; the signed-in user supplies ownership.
                String description = row.isMapped("description") ? row.get("description").trim() : null;
                String supplied = row.isMapped("category") ? row.get("category").trim()
                    : row.isMapped("type") ? row.get("type").trim() : "";
                String category = supplied.isBlank()
                    ? com.pocketledger.service.TransactionCategorizer.suggest(name, description, amount)
                    : com.pocketledger.service.TransactionCategorizer.normalize(supplied);
                if (category == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CSV row " + row.getRecordNumber() + " has an unsupported category.");
                if (name.length()>50 || description!=null && description.length()>250)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSV row " + row.getRecordNumber() + " exceeds a field length limit.");
                transactions.add(new Transaction(user, name, category, amount, date, description));
            }

            // Return all Transaction objects created from the CSV.
            // CSVDataExtraction.importTransactions() will then save this list
            // using transactionRepository.saveAll().
            return transactions;
        }
    }

    // Converts a date string from the CSV into a Java LocalDate.
    // rowNumber is included so error messages can tell the user
    // exactly which CSV row contains an invalid date.
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
