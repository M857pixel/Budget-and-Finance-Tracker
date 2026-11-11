/**
 * Executor for complex multi-step transaction operations requiring database interaction.
 *
 * Handles write actions that cannot be fulfilled by simple single-query repository calls.
 * Coordinates validation, database reads, modifications, and saves while enforcing
 * business rules and safety constraints.
 *
 * Supported Operations:
 * 1. CREATE_TRANSACTION: Validates and persists new transaction records
 * 2. DELETE_TRANSACTION: Single-record deletion with ambiguity protection (>1 match blocked)
 * 3. UPDATE_SINGLE_TRANSACTION: Partial updates preserving unspecified fields
 * 4. UPDATE_TRANSACTION_TYPE_BULK: Mass recategorization by keyword matching
 * 5. SEARCH_TRANSACTIONS_BY_KEYWORD: Name-based substring search with result limiting

 */



/*

    Allows for execution of complex multi-step operations that are not just simple repository calls.


    Bulk Updates: Finds transactions by keyword in description, recategorizes all
    Multi-Step Fix: Suggests categories for uncategorized transactions
    Safe Deletion: Prevents mass deletion by requiring user approval
    Parameter Parsing: Amount/date parsing with fallback defaults

    Operations handed:
    UPDATE_TRANSACTION_TYPE_BULK   --> bulkUpdateCategory()
    MULTI_STEP_TRANSACTION_FIX     --> multiStepFix()
    SEARCH_TRANSACTIONS_BY_KEYWORD --> searchTransactions()
    CREATE_TRANSACTION             --> createTransaction()
    DELETE_TRANSACTION             --> deleteTransaction()


    
*/

package com.pocketledger.service;

import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import org.springframework.stereotype.Service;
import com.pocketledger.model.User;
import java.math.BigDecimal;
import java.time.LocalDate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiOrchestratorService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "Clothing", "Entertainment", "Transport", "Groceries",
            "Salary", "Rent", "Other"
    );

    /** Initializes repository dependencies for complex operation execution. */
    public AiOrchestratorService(TransactionRepository tr, UserRepository ur) {
        this.transactionRepository = tr;
        this.userRepository = ur;
    }

    /** Dispatches a classified action to the appropriate complex operation handler. */
    public String executeComplexOperation(AiActionClassification classification, Long userId) {
        switch (classification.action()) {
            case "UPDATE_TRANSACTION_TYPE_BULK":
                return bulkUpdateCategory(userId, classification.parameters());
            case "SEARCH_TRANSACTIONS_BY_KEYWORD":
                return searchTransactions(userId, classification.parameters());
            case "CREATE_TRANSACTION" :
                return createTransaction(userId, classification.parameters());
            case "DELETE_TRANSACTION":
                return deleteTransaction(userId, classification.parameters());
            case "UPDATE_SINGLE_TRANSACTION":
                return updateSingleTransaction(userId, classification.parameters());
            default:
                throw new IllegalStateException("Unhandled action: " + classification.action());
        }
    }

    /** Recategorises all transactions whose name or description contains a given keyword. */
    private String bulkUpdateCategory(Long userId, Map<String, String> params) {
        String searchKeyword = params.get("search_criteria");
        String rawNewType = params.get("new_type");

        String newType = VALID_CATEGORIES.stream()// Enforce valid categories (case-insensitive)
                .filter(v -> v.equalsIgnoreCase(rawNewType))
                .findFirst()
                .orElse("Other");

        List<Transaction> matching = transactionRepository // Try name-first search
                .findByUser_UserIdAndTransactionNameContaining(userId, searchKeyword);

        if (matching.isEmpty()) {         // Fall back to description if name yields nothing
            matching = transactionRepository
                    .findByUser_UserIdAndTransactionDescriptionContaining(userId, searchKeyword);
        }

        if (matching.isEmpty()) {
            return "No transactions found matching '" + searchKeyword + "'.";
        }

        int updated = 0;
        for (Transaction t : matching) {
            t.updateDetails(null, newType, null, null, null);
            transactionRepository.save(t);
            updated++;
        }

        return String.format("Updated %d transaction%s from '%s' to '%s'.",
                updated, updated > 1 ? "s" : "",
                matching.get(0).getTransactionType(), newType);
    }

    /** Finds a single transaction by keyword, type, or date and applies partial or full updates. */
    private String updateSingleTransaction(Long userId, Map<String, String> params) {
        String keyword = params.get("keyword");
        String date = params.get("date");
        String type = params.get("type");

        List<Transaction> candidates;

        if (keyword != null && !keyword.isBlank()) {
            candidates = transactionRepository
                    .findByUser_UserIdAndTransactionNameContaining(userId, keyword);
        } else if (type != null && !type.isBlank()) {
            candidates = transactionRepository
                    .findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(userId, type);
        } else if (date != null && !date.isBlank()) {
            List<Transaction> all = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);
            try {
                LocalDate targetDate = LocalDate.parse(date.trim());
                candidates = all.stream()
                        .filter(t -> t.getTransactionDate() != null
                                && t.getTransactionDate().equals(targetDate))
                        .toList();
            } catch (Exception e) {
                return "I couldn't understand that date format.";
            }
        } else {
            return "I need to know which transaction to update.";
        }

        if (candidates.isEmpty()) {
            return "No transactions found matching your criteria.";
        }

        if (candidates.size() > 1) {
            StringBuilder msg = new StringBuilder();
            msg.append(String.format("Found %d matching transactions. ", candidates.size()));
            msg.append("Please be more specific to target a single transaction.");
            return msg.toString();
        }

        Transaction transaction = candidates.get(0);

        // Collect update fields (skip nulls to preserve existing values)
        String newName = params.get("update_name");
        String newType = params.get("update_type");
        String amountStr = params.get("amount");
        String updateDate = params.get("update_date");
        String newDescription = params.get("update_description") != null ? params.get("update_description") : params.get("description");

        BigDecimal newAmount = null;
        if (amountStr != null && !amountStr.isBlank()) {
            newAmount = AiActionParameters.amount(amountStr);
            if (newAmount == null) {
                return "I couldn't understand that amount.";
            }
        }

        LocalDate newDate = null;
        if (updateDate != null && !updateDate.isBlank()) {
            newDate = AiActionParameters.date(updateDate, null);
        }

        transaction.updateDetails(
                newName,
                newType,
                newAmount,
                newDate,
                newDescription
        );
        Transaction saved = transactionRepository.save(transaction);

        StringBuilder response = new StringBuilder();
        response.append("Updated \"");
        response.append(saved.getTransactionName()).append("\" (").append(saved.getTransactionType()).append(")");
        response.append(" — ");

        if (newName != null) response.append("name changed to \"").append(newName).append("\", ");
        if (newType != null) response.append("category changed to ").append(newType).append(", ");
        if (newAmount != null) response.append("amount changed to $").append(newAmount).append(", ");
        if (newDate != null) response.append("date changed to ").append(newDate).append(", ");
        if (newDescription != null) response.append("description changed to \"").append(newDescription).append("\"");

        response.setLength(response.length() - 2); // Remove trailing comma
        response.append(".");

        return response.toString();
    }

    /** Searches for transactions whose names contain a given keyword. */
    private String searchTransactions(Long userId, Map<String, String> params) {
        String keyword = params.get("keyword");
        List<Transaction> results = transactionRepository
                .findByUser_UserIdAndTransactionNameContaining(userId, keyword);

        if (results.isEmpty()) {
            return "No transactions found matching '" + keyword + "'.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d transactions matching '%s':\n\n", results.size(), keyword));

        for (Transaction t : results.stream().limit(10).toList()) {
            sb.append(String.format("• %s (%s): $%.2f on %s\n",
                    t.getTransactionName(),
                    t.getTransactionType(),
                    t.getTransactionAmount(),
                    t.getTransactionDate()));
        }
        return sb.toString();
    }

    /** Creates a new transaction record with validated parameters. */
    private String createTransaction(Long userId, Map<String, String> params) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalStateException("User not found"));

        String name = params.getOrDefault("name", "Untitled transaction");

        String rawType = params.getOrDefault("type", "Other").trim();
        String type = VALID_CATEGORIES.stream()
                .filter(valid -> valid.equalsIgnoreCase(rawType))
                .findFirst()
                .orElse("Other");

        BigDecimal amount = parseAmount(params.get("amount"));
        if (amount == null) {
            return "I couldn't read an amount from that request, so nothing was added.";
        }

        LocalDate date = parseDate(params.get("date"));
        String description = params.getOrDefault("description", "");

        Transaction saved = transactionRepository.save(
                new Transaction(user, name, type, amount, date, description));

        return String.format("Added \"%s\" (%s) for $%.2f on %s.",
                saved.getTransactionName(), saved.getTransactionType(),
                saved.getTransactionAmount(), saved.getTransactionDate());
    }

    /** Deletes a single transaction identified by keyword, type, or date. */
    private String deleteTransaction(Long userId, Map<String, String> params) {
        String keyword = params.get("keyword");
        String date = params.get("date");
        String type = params.get("type");

        List<Transaction> candidates;

        if (keyword != null && !keyword.isBlank()) {
            candidates = transactionRepository
                    .findByUser_UserIdAndTransactionNameContaining(userId, keyword);
        } else if (type != null && !type.isBlank()) {
            candidates = transactionRepository
                    .findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(userId, type);
        } else if (date != null && !date.isBlank()) {
            List<Transaction> all = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);
            try {
                LocalDate targetDate = LocalDate.parse(date.trim());
                candidates = all.stream()
                        .filter(t -> t.getTransactionDate() != null
                                && t.getTransactionDate().equals(targetDate))
                        .toList();
            } catch (Exception e) {
                return "I couldn't understand that date format.";
            }
        } else {
            return "I need more information to identify which transaction to delete. Please specify a name, category, or date.";
        }

        if (candidates.isEmpty()) {
            return "No transactions found matching your criteria.";
        }

        // Safety check: only allow deletion of single transaction after confirmation
        if (candidates.size() > 1) {
            StringBuilder msg = new StringBuilder();
            msg.append(String.format("Found %d matching transactions. ", candidates.size()));
            msg.append("To prevent accidental deletions, please be more specific. ");
            msg.append("Try combining criteria—e.g., \"delete coffee from yesterday\".");
            return msg.toString();
        }

        Transaction toDelete = candidates.get(0);
        transactionRepository.delete(toDelete);

        return String.format("Deleted transaction: \"%s\" (%s) for $%.2f on %s.",
                toDelete.getTransactionName(),
                toDelete.getTransactionType(),
                toDelete.getTransactionAmount(),
                toDelete.getTransactionDate());
    }

    /** Parses amount */
    private BigDecimal parseAmount(String raw) {
        return AiActionParameters.amount(raw);
    }

    /** Parses a string into a LocalDate, falling back to today on parse failure. */
    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}