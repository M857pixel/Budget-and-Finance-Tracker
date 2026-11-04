/**
 * Central coordinator for AI-powered natural language transaction management.
 *
 * This controller manages the complete intent classification workflow, routing user queries
 * through a confirmation control flow that distinguishes between read operations that are
 * executed immediately and write operations that requiring explicit user confirmation.
 *
 * Key Responsibilities:
 * 1. Authenticate requests and validate user sessions before processing
 * 2. Detect pending actions awaiting confirmation and route accordingly (CONFIRM_PENDING/CANCEL_PENDING)
 * 3. Forward queries to AiAssistantService for natural language classification via Mistral AI
 * 4. Validate classified action parameters before storage or execution
 * 5. Store write actions in pending state and request user confirmation
 * 6. Execute read actions immediately and format human-readable responses
 * 7. Route complex multi-step operations to AiOrchestratorService
 *
 * Confirmation Flow:
 * - First request with write intent → stored as pending, user asked "Shall I go ahead?"
 * - Follow-up "yes"/"confirm" → CONFIRM_PENDING triggers execution
 * - Follow-up "no"/"cancel" → CANCEL_PENDING clears pending action
 * - New unrelated query → pending action ignored, classified normally
 *
 * Action Categories:
 * READ (immediate): GET_MOST_RECENT_TRANSACTION, CURRENT_BALANCE_QUERY, GET_SPENDING_TOTAL,
 *                   GET_TRANSACTION_SUMMARY, RECENT_TRANSACTIONS_LIST
 * WRITE (confirm):  CREATE_TRANSACTION, UPDATE_TRANSACTION_TYPE_BULK, UPDATE_SINGLE_TRANSACTION,
 *                   DELETE_TRANSACTION
 */


package com.pocketledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import com.pocketledger.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;


@RestController
@RequestMapping("/api/assistant")
// Mirror CORS settings from LedgerController for frontend compatibility
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class AiAssistantController {

    private final TransactionRepository transactionRepository;
    private final AiAssistantService aiAssistantService;
    private final UserRepository userRepository;
    private final AiOrchestratorService aiOrchestratorService;
    private final AiPendingActionStore aiPendingActionStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Initializes all dependencies for handling assistant requests. */
    public AiAssistantController(TransactionRepository transactionRepository, AiAssistantService aiAssistantService, UserRepository userRepository,
                                 AiOrchestratorService aiOrchestratorService, AiPendingActionStore aiPendingActionStore) {
        this.transactionRepository = transactionRepository;
        this.aiAssistantService = aiAssistantService;
        this.userRepository = userRepository;
        this.aiOrchestratorService = aiOrchestratorService;
        this.aiPendingActionStore = aiPendingActionStore;
    }

    public record QueryRequest(String query) {}
    public record AssistantResponse(String action, String result) {}

    /** Main endpoint: receives a natural language query, classifies intent, and executes or requests confirmation. */
    @PostMapping("/query")
    public ResponseEntity<AssistantResponse> query(@RequestBody QueryRequest body, HttpServletRequest request) {
        Long userId = requireUserId(request);

        if (body == null || body.query() == null || body.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query is required.");
        }
        String pendingDescription = aiPendingActionStore.peek(userId) // 1. Is something already waiting for confirmation?
                .map(this::describe)
                .orElse(null);

        String actionJson; // 2. Classify, giving the model the pending context
        try {
            actionJson = aiAssistantService.classify(body.query(), pendingDescription);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is unavailable right now.", e);
        }
        AiActionClassification classification = parseAction(actionJson);

        switch (classification.action()) { // 3. Confirmation control flow — handled BEFORE any routing
            case "CONFIRM_PENDING" -> {
                AiActionClassification toRun = aiPendingActionStore.consume(userId).orElse(null);
                if (toRun == null) {
                    return ResponseEntity.ok(new AssistantResponse("NONE",
                            "There's nothing waiting for confirmation right now."));
                }
                String raw = execute(toRun, userId);
                return ResponseEntity.ok(new AssistantResponse(toRun.action(),
                        formatResponse(toRun.action(), raw)));
            }
            case "CANCEL_PENDING" -> {
                aiPendingActionStore.clear(userId);
                return ResponseEntity.ok(new AssistantResponse("CANCEL_PENDING",
                        "No problem — I've cancelled that."));
            }
            case "NONE" -> {
                return ResponseEntity.ok(new AssistantResponse("NONE",
                        "I can only help with questions about your own transactions."));
            }
            default -> { /* fall through to normal handling */ }
        }

        if (WRITE_ACTIONS.contains(classification.action())) { // 4. Write actions must be confirmed first — stashes them, doesn't execute
            String problem = validate(classification);
            if (problem != null) {
                return ResponseEntity.ok(new AssistantResponse("CLARIFICATION_REQUIRED", problem));
            }
            aiPendingActionStore.put(userId, classification);
            return ResponseEntity.ok(new AssistantResponse("CONFIRMATION_REQUIRED",
                    describe(classification) + " Shall I go ahead?"));
        }

        String raw = execute(classification, userId); // 5. Safe read actions execute immediately
        return ResponseEntity.ok(new AssistantResponse(classification.action(),
                formatResponse(classification.action(), raw)));
    }

    // Helper methods

    /** Parses AI response JSON into a structured action classification object. */
    private AiActionClassification parseAction(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            String action = root.path("action").asText("").trim().toUpperCase();
            if (action.isEmpty()) {
                return new AiActionClassification("NONE", new HashMap<>());
            }
            Map<String, String> parameters = new HashMap<>();
            JsonNode params = root.path("parameters");
            if (params.isObject()) {
                params.fieldNames().forEachRemaining(key ->
                        parameters.put(key, params.get(key).asText()));
            }
            return new AiActionClassification(action, parameters);
        } catch (Exception e) {
            // Not JSON — treat as bare action name
            String action = raw == null ? "NONE" : raw.trim().toUpperCase();
            return new AiActionClassification(action, new HashMap<>());
        }
    }

    /** Validates that a classified action has all required parameters before storing or executing. */
    private String validate(AiActionClassification c) {
        Map<String, String> p = c.parameters();
        return switch (c.action()) {
            case "CREATE_TRANSACTION" -> {
                BigDecimal amount = AiActionParameters.amount(p.get("amount"));
                if (amount == null || amount.signum() == 0) {
                    yield "I need a non-zero amount. How much was this transaction for?";
                }
                if (AiActionParameters.isBlank(p.get("name"))) {
                    yield "What should I call this transaction?";
                }
                yield null;
            }
            case "UPDATE_TRANSACTION_TYPE_BULK" -> {
                if (AiActionParameters.isBlank(p.get("search_criteria")))
                    yield "Which transactions should I update?";
                if (AiActionParameters.isBlank(p.get("new_type")))
                    yield "What category should they be moved to?";
                yield null;
            }
            case "UPDATE_SINGLE_TRANSACTION" -> {
                // At least one identifier
                boolean hasKeyword = !AiActionParameters.isBlank(p.get("keyword"));
                boolean hasType = !AiActionParameters.isBlank(p.get("type"));
                boolean hasDate = !AiActionParameters.isBlank(p.get("date"));
                boolean hasIdentifier = hasKeyword || hasType || hasDate;


                if (!hasIdentifier) {
                    yield "I need to know which transaction to update. Please specify a name, category, or date.";
                }

                // At least one update field
                boolean hasName = !AiActionParameters.isBlank(p.get("update_name"));
                boolean hasUpdateType = !AiActionParameters.isBlank(p.get("update_type"));
                boolean hasAmount = !AiActionParameters.isBlank(p.get("amount"));
                boolean hasUpdateDate = !AiActionParameters.isBlank(p.get("update_date"));
                boolean hasDescription = !AiActionParameters.isBlank(p.get("update_description"));
                boolean hasUpdateField = hasName || hasUpdateType || hasAmount || hasUpdateDate || hasDescription;

                if (!hasUpdateField) {
                    yield "What would you like to change about this transaction?";
                }

                // Validate amount if provided
                if (hasAmount) {
                    BigDecimal amount = AiActionParameters.amount(p.get("amount"));
                    if (amount == null) {
                        yield "I couldn't understand that amount.";
                    }
                }

                yield null;
            }

            case "DELETE_TRANSACTION" -> {
                // Require at least one identifier
                boolean hasKeyword = !AiActionParameters.isBlank(p.get("keyword"));
                boolean hasType = !AiActionParameters.isBlank(p.get("type"));
                boolean hasDate = !AiActionParameters.isBlank(p.get("date"));

                if (!hasKeyword && !hasType && !hasDate) {
                    yield "I need more information to identify which transaction to delete. Please specify a name, category, or date.";
                }
                yield null;
            }
            default -> null;
        };
    }

    private static final Set<String> WRITE_ACTIONS = Set.of(
            "UPDATE_TRANSACTION_TYPE_BULK",
            "UPDATE_SINGLE_TRANSACTION",
            "CREATE_TRANSACTION",
            "DELETE_TRANSACTION");

    private static final Set<String> COMPLEX_ACTIONS = Set.of(
            "UPDATE_TRANSACTION_TYPE_BULK",
            "UPDATE_SINGLE_TRANSACTION",
            "SEARCH_TRANSACTIONS_BY_KEYWORD",
            "CREATE_TRANSACTION",
            "DELETE_TRANSACTION");



    /** Human-readable rendering of a classified action, used for confirmation prompts. */
    private String describe(AiActionClassification c) {
        Map<String, String> p = c.parameters();
        return switch (c.action()) {
            case "UPDATE_TRANSACTION_TYPE_BULK" -> String.format(
                    "I'll recategorise every transaction whose description contains \"%s\" to \"%s\".",
                    p.getOrDefault("search_criteria", "?"), p.getOrDefault("new_type", "?"));
            case "CREATE_TRANSACTION" -> String.format(
                    "I'll add a %s called \"%s\" for $%s dated %s.",
                    p.getOrDefault("type", "transaction"),
                    p.getOrDefault("name", "unnamed"),
                    p.getOrDefault("amount", "?"),
                    p.getOrDefault("date", "today"));
            case "DELETE_TRANSACTION" -> {
                StringBuilder conditions = new StringBuilder();
                if (!AiActionParameters.isBlank(p.get("keyword")))
                    conditions.append("name containing \"").append(p.get("keyword")).append("\"");
                if (!AiActionParameters.isBlank(p.get("type"))) {
                    if (conditions.length() > 0) conditions.append(" and ");
                    conditions.append("category ").append(p.get("type"));
                }
                if (!AiActionParameters.isBlank(p.get("date"))) {
                    if (conditions.length() > 0) conditions.append(" and ");
                    conditions.append("date ").append(p.get("date"));
                }
                yield "I'll delete the transaction matching " + conditions + ".";
            }
            case "UPDATE_SINGLE_TRANSACTION" -> {
                StringBuilder changes = new StringBuilder();
                if (!AiActionParameters.isBlank(p.get("update_name")))
                    changes.append("name to \"").append(p.get("update_name")).append("\"");
                if (!AiActionParameters.isBlank(p.get("update_type"))) {
                    if (changes.length() > 0) changes.append(", ");
                    changes.append("category to ").append(p.get("update_type"));
                }
                if (!AiActionParameters.isBlank(p.get("amount"))) {
                    if (changes.length() > 0) changes.append(", ");
                    changes.append("amount to $").append(p.get("amount"));
                }
                if (!AiActionParameters.isBlank(p.get("update_date"))) {
                    if (changes.length() > 0) changes.append(", ");
                    changes.append("date to ").append(p.get("update_date"));
                }
                if (!AiActionParameters.isBlank(p.get("description"))) {
                    if (changes.length() > 0) changes.append(", ");
                    changes.append("description to \"").append(p.get("description")).append("\"");
                }

                StringBuilder ident = new StringBuilder();
                if (!AiActionParameters.isBlank(p.get("keyword")))
                    ident.append("name containing \"").append(p.get("keyword")).append("\"");
                if (!AiActionParameters.isBlank(p.get("type"))) {
                    if (ident.length() > 0) ident.append(" and ");
                    ident.append("category ").append(p.get("type"));
                }
                if (!AiActionParameters.isBlank(p.get("date"))) {
                    if (ident.length() > 0) ident.append(" and ");
                    ident.append("date ").append(p.get("date"));
                }

                yield "I'll update the transaction matching " + ident + ", changing " + changes + ".";
            }

            default -> "I'll carry out " + c.action() + ".";
        };
    }

    /** Routes an action to either complex orchestration or simple execution based on type. */
    private String execute(AiActionClassification c, Long userId) {
        return COMPLEX_ACTIONS.contains(c.action())
                ? aiOrchestratorService.executeComplexOperation(c, userId)
                : executeSimpleAction(c, userId);
    }

    /** Dispatches simple read actions to their specific handler methods. */
    private String executeSimpleAction(AiActionClassification c, Long userId) {
        return switch (c.action()) {
            case "GET_MOST_RECENT_TRANSACTION" -> mostRecentTransaction(userId);
            case "GET_TRANSACTION_SUMMARY" -> getTransactionSummary(userId, c.parameters());
            case "GET_SPENDING_TOTAL" -> spendingTotal(userId, c.parameters());
            case "CURRENT_BALANCE_QUERY" -> currentBalanceQuery(userId);
            case "RECENT_TRANSACTIONS_LIST" -> recentTransactionsList(userId, 5);
            default -> "Unknown action: " + c.action();
        };
    }

    /** Checks if a transaction date falls within an optional start/end range. */
    private static boolean inRange(Transaction t, LocalDate start, LocalDate end) {
        LocalDate d = t.getTransactionDate();
        if (d == null) return start == null && end == null;
        return (start == null || !d.isBefore(start)) && (end == null || !d.isAfter(end));
    }

    /** Calculates total expenses within a date range, returning a formatted summary string. */
    private String spendingTotal(Long userId, Map<String, String> p) {
        LocalDate start = AiActionParameters.date(p.get("start_date"), null);
        LocalDate end   = AiActionParameters.date(p.get("end_date"), null);

        List<Transaction> txns = transactionRepository
                .findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, start, end))
                .toList();

        BigDecimal spent = txns.stream()
                .map(Transaction::getTransactionAmount)
                .filter(a -> a != null && a.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long count = txns.stream()
                .filter(t -> t.getTransactionAmount() != null
                        && t.getTransactionAmount().signum() < 0)
                .count();

        String period = describePeriod(start, end);
        if (count == 0) return "You haven't recorded any spending" + period + ".";
        return String.format("You've spent $%.2f%s across %d transaction%s.",
                spent, period, count, count == 1 ? "" : "s");
    }

    /** Returns a human-readable description of a date period (e.g., "since 2026-11-01"). */
    private static String describePeriod(LocalDate start, LocalDate end) {
        if (start == null && end == null) return " in total";
        if (start != null && end != null) return " between " + start + " and " + end;
        if (start != null) return " since " + start;
        return " up to " + end;
    }

    /** Extracts and validates the authenticated user ID from the HTTP session. */
    private Long requireUserId(HttpServletRequest request) {
        var session = request.getSession(false);

        Object authenticatedUserId;
        if (session == null) {
            authenticatedUserId = null;
        } else {
            authenticatedUserId = session.getAttribute("userId");
        }


        if (authenticatedUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }

        if (authenticatedUserId instanceof Long userId) {
            return userId;
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Invalid user ID in session."
        );
    }
    /** Formats raw action output into a user-facing response before returning it. */
    private String formatResponse(String action, String rawData) {
        return switch (action) {
            case "CURRENT_BALANCE_QUERY" -> {
                if (rawData.contains("$0")) {
                    yield "Your current balance is $0.00.";
                }
                yield "Your current account balance is "
                        + rawData.replace("Your current balance is ", "") + ".";
            }
            case "GET_TRANSACTION_SUMMARY" -> "Here's your transaction summary:\n\n" + rawData;
            case "RECENT_TRANSACTIONS_LIST" -> "Here are your recent transactions:\n\n" + rawData;
            default -> rawData; // Complex operations already formatted
        };
    }


    /** Prints the most recent transaction in full. */
    private String mostRecentTransaction(Long userId) {
        List<Transaction> transactions =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        if (transactions.isEmpty()) {
            return "You haven't recorded any transactions yet.";
        }

        Transaction t = transactions.get(0);

        StringBuilder sb = new StringBuilder("Your most recent transaction was ");
        sb.append('"').append(orDefault(t.getTransactionName(), "Untitled")).append('"');
        sb.append(" (").append(orDefault(t.getTransactionType(), "Uncategorized")).append(')');
        sb.append(" for ").append(money(t.getTransactionAmount()));
        sb.append(" on ").append(t.getTransactionDate() == null ? "an unknown date" : t.getTransactionDate());

        String description = t.getTransactionDescription();
        if (description != null && !description.isBlank()) {
            sb.append("\n   ").append(description.trim());
        }

        return sb.append('.').toString();
    }

    /** Returns a fallback string when a transaction field is null or blank. */
    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Formats a BigDecimal amount as currency, handling negative values correctly. */
    private static String money(BigDecimal amount) {
        if (amount == null) return "an unspecified amount";
        BigDecimal rounded = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        return rounded.signum() < 0
                ? "-$" + rounded.abs().toPlainString()
                : "$" + rounded.toPlainString();
    }

    /**
     * Aggregates transactions by category, calculating income, expenses, and net totals.
     */
    private String getTransactionSummary(Long userId, Map<String, String> p) {
        LocalDate start = AiActionParameters.date(p.get("start_date"), null);
        LocalDate end   = AiActionParameters.date(p.get("end_date"), null);

        List<Transaction> transactions = transactionRepository
                .findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, start, end))
                .toList();

        if (transactions.isEmpty()) {
            return (start == null && end == null)
                    ? "You have no transactions to summarize."
                    : "You have no transactions in that period.";
        }

        Map<String, BigDecimal> totalByType = new LinkedHashMap<>();
        Map<String, Integer> countByType = new HashMap<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;

        for (Transaction t : transactions) {
            String type = t.getTransactionType();
            if (type == null || type.isBlank()) type = "Uncategorized";
            BigDecimal amount = t.getTransactionAmount() == null
                    ? BigDecimal.ZERO : t.getTransactionAmount();

            countByType.merge(type, 1, Integer::sum);
            totalByType.merge(type, amount, BigDecimal::add);

            if (amount.signum() < 0) expenses = expenses.add(amount.abs());
            else income = income.add(amount);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Transaction Summary").append(describePeriod(start, end)).append(":\n");
        for (Map.Entry<String, BigDecimal> e : totalByType.entrySet()) {
            int n = countByType.get(e.getKey());
            summary.append(String.format("- %s: %d transaction%s, totaling $%.2f%n",
                    e.getKey(), n, n == 1 ? "" : "s", e.getValue()));
        }
        summary.append(String.format("%nReceived: $%.2f | Spent: $%.2f | Net: $%.2f",
                income, expenses, income.subtract(expenses)));
        return summary.toString();
    }

    /** Calculates the current month's net balance from all recorded transactions. */
    private String currentBalanceQuery(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        BigDecimal balance = transactionRepository
                .findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, monthStart, today))
                .map(Transaction::getTransactionAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return String.format("$%.2f for %s",
                balance, today.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()));
    }



    /** Returns up to N most recent transactions with full details formatted for display. */
    private String recentTransactionsList(Long userId, int limit) {
        List<Transaction> transactions = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        if (transactions.isEmpty()) {
            return "You have no transactions to display.";
        }

        // Transaction limit to prevent excessive data transfer
        int displayCount = Math.min(limit, transactions.size());
        List<Transaction> recent = transactions.subList(0, displayCount);

        StringBuilder result = new StringBuilder();
        result.append(String.format("Your %d most recent transactions:\n\n", displayCount));

        int index = 1;
        for (Transaction transaction : recent) {
            result.append(String.format("%d. %s | %s | $%.2f | %s\n", index++,
                    transaction.getTransactionName(),
                    transaction.getTransactionType(),
                    transaction.getTransactionAmount(),
                    transaction.getTransactionDate()));

            if (transaction.getTransactionDescription() != null && !transaction.getTransactionDescription().isBlank()) {
                result.append(String.format("   Description: %s\n", transaction.getTransactionDescription()));
            }
            result.append("\n");
        }

        return result.toString();
    }
}

