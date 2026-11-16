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
import com.pocketledger.repository.MonthlyBudgetRepository;
import com.pocketledger.model.MonthlyBudget;
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

import java.time.YearMonth;
import java.util.*;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;


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
    private final CurrencyConversionService currencyConversionService;
    private final MonthlyBudgetRepository monthlyBudgetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Initializes all dependencies for handling assistant requests. */
    public AiAssistantController(TransactionRepository transactionRepository, AiAssistantService aiAssistantService, UserRepository userRepository,
                                 AiOrchestratorService aiOrchestratorService, AiPendingActionStore aiPendingActionStore,
                                 CurrencyConversionService currencyConversionService, MonthlyBudgetRepository monthlyBudgetRepository) {
        this.transactionRepository = transactionRepository;
        this.aiAssistantService = aiAssistantService;
        this.userRepository = userRepository;
        this.aiOrchestratorService = aiOrchestratorService;
        this.aiPendingActionStore = aiPendingActionStore;
        this.currencyConversionService = currencyConversionService;
        this.monthlyBudgetRepository = monthlyBudgetRepository;
    }

    public record QueryRequest(String query) {}
    public record AssistantResponse(String action, String result) {}
    public record ChartPoint(String label, String amount) {}
    public record AnalysisResponse(String action, String result, String chart,
                                   String startDate, String endDate, String category,
                                   List<ChartPoint> data, String total, boolean empty) {}

    /** Main endpoint: receives a natural language query, classifies intent, and executes or requests confirmation. */
    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest body, HttpServletRequest request) {
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
        List<AiActionClassification> actions = parseActions(actionJson);
        StringBuilder reply = new StringBuilder();

        for (AiActionClassification classification : actions) {
            switch (classification.action()) {
                case "CONFIRM_PENDING" -> {
                    AiActionClassification toRun = aiPendingActionStore.consume(userId).orElse(null);
                    if (toRun == null) {
                        return ResponseEntity.ok(new AssistantResponse("NONE",
                                "There's nothing waiting for confirmation right now."));
                    }
                    String problem = validate(toRun);           // re-check amended actions
                    if (problem != null) {
                        aiPendingActionStore.put(userId, toRun); // stay pending until the detail arrives
                        return ResponseEntity.ok(new AssistantResponse("CLARIFICATION_REQUIRED", problem));
                    }
                    return ResponseEntity.ok(new AssistantResponse(toRun.action(),
                            formatResponse(toRun.action(), execute(toRun, userId))));
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
                default -> {
                    if (WRITE_ACTIONS.contains(classification.action())) {
                        String problem = validate(classification);
                        if (problem != null) {
                            aiPendingActionStore.put(userId, classification); // keep awaiting the detail
                            return ResponseEntity.ok(new AssistantResponse("CLARIFICATION_REQUIRED", problem));
                        }
                        aiPendingActionStore.put(userId, classification);
                        return ResponseEntity.ok(new AssistantResponse("CONFIRMATION_REQUIRED",
                                describe(classification) + " Shall I go ahead?"));
                    }
                    String formatted = formatResponse(classification.action(),
                            execute(classification, userId));
                    AnalysisResponse analysis = switch (classification.action()) {
                        case "GET_SPENDING_TOTAL"       -> monthlyExpenses(userId, classification.parameters(), formatted);
                        case "GET_TRANSACTION_SUMMARY"  -> spendingByCategory(userId, classification.parameters(), formatted);
                        case "PROJECT_MONTHLY_SPENDING" -> projectionChart(userId, classification.parameters(), formatted);
                        case "PROJECT_YEARLY_SPENDING" -> yearlyProjectionChart(userId, formatted);
                        case "PROJECT_CATEGORY_SPENDING" -> categoryProjectionChart(userId, classification.parameters(), formatted);
                        default -> null;
                    };
                    if (analysis != null) return ResponseEntity.ok(analysis);
                    reply.append(formatted).append('\n');
                }
            }
        }
        return ResponseEntity.ok(new AssistantResponse(
                actions.size() > 1 ? "MULTI" : actions.get(0).action(), reply.toString().trim()));
    }

    // Helper methods

    private static Map<String,String> c0(AiActionClassification c) { return c.parameters(); }

    /** Parses AI response JSON into a structured action classification object. */
    private static final int MAX_ACTIONS_PER_MESSAGE = 5;

    private List<AiActionClassification> parseActions(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode items = root.isArray() ? root : objectMapper.createArrayNode().add(root);
            List<AiActionClassification> out = new ArrayList<>();
            for (JsonNode item : items) {
                if (!item.isObject()) continue;
                String action = item.path("action").asText("").trim().toUpperCase();
                if (action.isEmpty()) continue;
                Map<String, String> params = new HashMap<>();
                JsonNode ps = item.path("parameters");
                if (ps.isObject()) {
                    ps.fieldNames().forEachRemaining(k -> params.put(k, ps.get(k).asText()));
                }
                out.add(new AiActionClassification(action, params));
                if (out.size() == MAX_ACTIONS_PER_MESSAGE) break;
            }
            return out.isEmpty() ? List.of(new AiActionClassification("NONE", new HashMap<>())) : out;
        } catch (Exception e) {
            String action = raw == null ? "NONE" : raw.trim().toUpperCase();
            return List.of(new AiActionClassification(action, new HashMap<>()));
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
            case "RECENT_TRANSACTIONS_LIST" -> recentTransactionsList(userId, parseCount(c.parameters()));
            case "CONVERT_CURRENCY" -> convertCurrency(c.parameters());
            case "GET_MONTHLY_BUDGET" -> monthlyBudget(userId, c.parameters());
            case "COMPARE_SPENDING" -> compareSpending(userId, c.parameters());
            case "PROJECT_MONTHLY_SPENDING" -> spendProjection(userId, c.parameters());
            case "PROJECT_YEARLY_SPENDING" -> yearlyProjection(userId);
            case "PROJECT_CATEGORY_SPENDING" -> categoryProjection(userId, c.parameters());
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

        long count = txns.stream().filter(t -> t.getTransactionAmount() != null && t.getTransactionAmount().signum() < 0).count();

        String period = describePeriod(start, end);
        if (count == 0) return "You haven't recorded any spending" + period + ".";
        return String.format("You've spent $%.2f%s across %d transaction%s.",
                spent, period, count, count == 1 ? "" : "s");
    }

    private record SpendingSummary(BigDecimal spent, long count) {}

    private SpendingSummary summarizeSpending(Long userId, LocalDate start, LocalDate end) {
        List<Transaction> txns = transactionRepository
                .findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, start, end)).toList();
        BigDecimal spent = txns.stream().map(Transaction::getTransactionAmount)
                .filter(a -> a != null && a.signum() < 0).map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = txns.stream().filter(t -> t.getTransactionAmount() != null
                && t.getTransactionAmount().signum() < 0).count();
        return new SpendingSummary(spent, count);
    }

    private String compareSpending(Long userId, Map<String, String> p) {
        LocalDate aStart = AiActionParameters.date(p.get("start_date_a"), null);
        LocalDate aEnd   = AiActionParameters.date(p.get("end_date_a"), null);
        LocalDate bStart = AiActionParameters.date(p.get("start_date_b"), null);
        LocalDate bEnd   = AiActionParameters.date(p.get("end_date_b"), null);
        SpendingSummary a = summarizeSpending(userId, aStart, aEnd);
        SpendingSummary b = summarizeSpending(userId, bStart, bEnd);
        BigDecimal diff = a.spent().subtract(b.spent());
        String comparison = diff.signum() > 0 ? "more" : diff.signum() < 0 ? "less" : "the same as";
        if (diff.signum() == 0) {
            return String.format("You spent the same, $%.2f, in both periods (%s to %s and %s to %s).",
                    a.spent(), aStart, aEnd, bStart, bEnd);
        }
        String word = diff.signum() > 0 ? "more" : "less";
        return String.format("You spent $%s %s between %s and %s than between %s and %s ($%.2f vs $%.2f).",
                diff.abs().toPlainString(), word, aStart, aEnd, bStart, bEnd, a.spent(), b.spent());
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

    /** Converts a given amount from one currency to another.*/
    private String convertCurrency(Map<String, String> p) {
        BigDecimal amount = AiActionParameters.amount(p.get("amount"));
        String from = p.get("from_currency");
        String to = p.get("to_currency");
        if (amount == null || from == null || to == null) {
            return "I need an amount and both currencies to convert.";
        }
        try {
            return currencyConversionService.formatConversion(amount, from, to);
        } catch (IllegalArgumentException | CurrencyConversionService.CurrencyConversionException e) {
            return "I couldn't perform that conversion right now.";
        }
    }

    private static int parseCount(Map<String, String> p) {
        String raw = p.get("count");
        if (raw == null) return 5;
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    /** Monthly expense totals; "line" suits longer ranges, otherwise "bar". */
    private AnalysisResponse monthlyExpenses(Long userId, Map<String, String> p, String formatted) {
        LocalDate start = AiActionParameters.date(p.get("start_date"), null);
        LocalDate end   = AiActionParameters.date(p.get("end_date"), null);

        List<Transaction> dated = transactionRepository
                .findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> t.getTransactionDate() != null && inRange(t, start, end))
                .toList();

        TreeMap<YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (Transaction t : dated) {
            BigDecimal a = t.getTransactionAmount();
            if (a != null && a.signum() < 0)
                byMonth.merge(YearMonth.from(t.getTransactionDate()), a.abs(), BigDecimal::add);
        }
        if (byMonth.isEmpty()) return null;

        YearMonth first = start != null ? YearMonth.from(start) : byMonth.firstKey();
        YearMonth last  = end   != null ? YearMonth.from(end)   : byMonth.lastKey();

        List<ChartPoint> points = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            BigDecimal v = byMonth.getOrDefault(m, BigDecimal.ZERO);
            total = total.add(v);
            points.add(new ChartPoint(m.toString(), v.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()));
        }
        String chart = first.until(last, java.time.temporal.ChronoUnit.MONTHS) >= 11 ? "line" : "bar";
        return new AnalysisResponse("monthly_expenses", formatted, chart,
                first.atDay(1).toString(), last.atEndOfMonth().toString(), null,
                List.copyOf(points), total.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), false);
    }

    /** Expense totals per category, largest first; a donut fits a category share. */
    private AnalysisResponse spendingByCategory(Long userId, Map<String, String> p, String formatted) {
        LocalDate start = AiActionParameters.date(p.get("start_date"), null);
        LocalDate end   = AiActionParameters.date(p.get("end_date"), null);

        Map<String, BigDecimal> byCategory = new HashMap<>();
        transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, start, end))
                .forEach(t -> {
                    BigDecimal a = t.getTransactionAmount();
                    if (a != null && a.signum() < 0) {
                        String type = orDefault(t.getTransactionType(), "Other");
                        byCategory.merge(type, a.abs(), BigDecimal::add);
                    }
                });
        if (byCategory.isEmpty()) return null;

        List<ChartPoint> points = byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new ChartPoint(e.getKey(), e.getValue().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()))
                .toList();
        BigDecimal total = points.stream()
                .map(pt -> new BigDecimal(pt.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AnalysisResponse("spending_by_category", formatted, "donut",
                start == null ? null : start.toString(), end == null ? null : end.toString(), null,
                List.copyOf(points), total.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), false);
    }

    /** Looks up the user's monthly budget for an optional "month" parameter (YYYY-MM). */
    private String monthlyBudget(Long userId, Map<String, String> p) {
        YearMonth month = parseMonth(p.get("month"));
        return monthlyBudgetRepository.findByUser_UserIdAndBudgetMonth(userId, month.atDay(1))
                .map(b -> String.format("Your budget for %s is %s.", month, money(b.getBudgetAmount())))
                .orElse("You haven't set a budget for " + month + ".");
    }

    /** Parses a YYYY-MM month string, defaulting to the current month when absent. */
    private static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(raw.trim());
        } catch (Exception e) {
            return YearMonth.now();
        }
    }

    /** Clamps the lookback window to 1-12 completed months (default 3). */
    private static int parseMonths(String raw) {
        if (raw == null) return 3;
        try {
            return Math.max(1, Math.min(12, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Sums absolute expense amounts (negative values only) within a date range. */
    private BigDecimal expenseTotal(List<Transaction> txns, LocalDate start, LocalDate end) {
        return txns.stream()
                .filter(t -> inRange(t, start, end))
                .map(Transaction::getTransactionAmount)
                .filter(a -> a != null && a.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Everything the projection text and chart both need, computed once. */
    private record ProjectionSnapshot(YearMonth current, BigDecimal monthToDate, int months,
                                      BigDecimal projectedTotal, BigDecimal historyAvg,
                                      BigDecimal budget, List<ChartPoint> monthlyPoints) {}

    /** Builds the pace forecast, budget lookup, and history points for the projection. */
    private ProjectionSnapshot computeProjection(Long userId, Map<String, String> p) {
        int lookback = parseMonths(p.get("months"));
        YearMonth current = YearMonth.now();
        LocalDate today = LocalDate.now();
        List<Transaction> txns = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        // Completed-month history totals (also become the chart's bars)
        List<ChartPoint> points = new ArrayList<>();
        BigDecimal historySum = BigDecimal.ZERO;
        int monthsWithData = 0;
        for (int i = lookback; i >= 1; i--) {
            YearMonth m = current.minusMonths(i);
            BigDecimal total = expenseTotal(txns, m.atDay(1), m.atEndOfMonth());
            if (total.signum() > 0) { historySum = historySum.add(total); monthsWithData++; }
            points.add(new ChartPoint(m.toString(),
                    total.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()));
        }

        // Pace extrapolation for the current month
        BigDecimal monthToDate = expenseTotal(txns, current.atDay(1), today);
        int daysElapsed = today.getDayOfMonth();
        int daysInMonth = current.lengthOfMonth();
        BigDecimal dailyRate = daysElapsed == 0 ? BigDecimal.ZERO
                : monthToDate.divide(BigDecimal.valueOf(daysElapsed), 4, java.math.RoundingMode.HALF_EVEN);
        BigDecimal projectedTotal = monthToDate
                .add(dailyRate.multiply(BigDecimal.valueOf(daysInMonth - daysElapsed)))
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
        points.add(new ChartPoint(current + " (proj.)", projectedTotal.toPlainString()));

        BigDecimal historyAvg = monthsWithData == 0 ? BigDecimal.ZERO
                : historySum.divide(BigDecimal.valueOf(monthsWithData), 2, java.math.RoundingMode.HALF_EVEN);

        BigDecimal budget = monthlyBudgetRepository
                .findByUser_UserIdAndBudgetMonth(userId, current.atDay(1))
                .map(MonthlyBudget::getBudgetAmount)
                .orElse(null);

        return new ProjectionSnapshot(current, monthToDate, lookback, projectedTotal,
                historyAvg, budget, List.copyOf(points));
    }

    /** Largest month-to-date expense category, used for the trimming suggestion. */
    private Map.Entry<String, BigDecimal> topExpenseCategory(Long userId, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> byCategory = new HashMap<>();
        transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId).stream()
                .filter(t -> inRange(t, start, end))
                .forEach(t -> {
                    BigDecimal a = t.getTransactionAmount();
                    if (a != null && a.signum() < 0) {
                        byCategory.merge(orDefault(t.getTransactionType(), "Other"), a.abs(), BigDecimal::add);
                    }
                });
        return byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
    }

    /** Read handler: forecast text plus budget-aware suggestions. */
    private String spendProjection(Long userId, Map<String, String> p) {
        ProjectionSnapshot s = computeProjection(userId, p);
        if (s.monthToDate().signum() == 0 && s.historyAvg().signum() == 0) {
            return "You haven't recorded any spending yet, so there's nothing to project.";
        }

        LocalDate today = LocalDate.now();
        int daysRemaining = s.current().lengthOfMonth() - today.getDayOfMonth();

        StringBuilder out = new StringBuilder(String.format(
                "Month-to-date you've spent $%.2f. At this pace you're projected to end %s at $%.2f.",
                s.monthToDate(), s.current(), s.projectedTotal()));

        if (s.historyAvg().signum() > 0) {
            BigDecimal diff = s.projectedTotal().subtract(s.historyAvg());
            String word = diff.signum() > 0 ? "above" : diff.signum() < 0 ? "below" : "in line with";
            out.append(String.format(" That's %s your %d-month average ($%.2f).",
                    word, s.months(), s.historyAvg()));
        }

        out.append("\n\nSuggestions:");

        if (s.budget() != null) {
            BigDecimal over = s.projectedTotal().subtract(s.budget());
            if (over.signum() > 0) {
                out.append(String.format("%n- You're on pace to exceed your $%.2f budget by $%.2f.",
                        s.budget(), over));
                Map.Entry<String, BigDecimal> top =
                        topExpenseCategory(userId, s.current().atDay(1), today);
                if (top != null) {
                    out.append(String.format(
                            " Trimming \"%s\" (your largest category, $%.2f so far) by ~20%% would close about $%.2f of the gap.",
                            top.getKey(), top.getValue(),
                            top.getValue().multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_EVEN)));
                }
            } else {
                out.append(String.format(
                        "%n- You're on pace to finish $%.2f under your $%.2f budget — consider moving the surplus to savings.",
                        over.abs(), s.budget()));
            }
            BigDecimal allowance = s.budget().subtract(s.monthToDate());
            if (daysRemaining > 0 && allowance.signum() > 0) {
                out.append(String.format(
                        "%n- To stay in budget, keep remaining daily spending at or below $%.2f for the next %d day%s.",
                        allowance.divide(BigDecimal.valueOf(daysRemaining), 2, java.math.RoundingMode.HALF_EVEN),
                        daysRemaining, daysRemaining == 1 ? "" : "s"));
            }
        } else {
            BigDecimal suggested = s.projectedTotal().signum() > 0 ? s.projectedTotal() : s.historyAvg();
            out.append(String.format(
                    "%n- You haven't set a budget for %s. Based on your pace, one near $%.0f would be realistic.",
                    s.current(), suggested.setScale(-2, java.math.RoundingMode.HALF_UP)));
        }
        return out.toString();
    }

    /** Bar chart: recent completed months plus the projected total for the current month. */
    private AnalysisResponse projectionChart(Long userId, Map<String, String> p, String formatted) {
        ProjectionSnapshot s = computeProjection(userId, p);
        if (s.monthToDate().signum() == 0 && s.historyAvg().signum() == 0) return null;
        return new AnalysisResponse("spending_projection", formatted, "bar",
                s.current().minusMonths(s.months()).atDay(1).toString(),
                s.current().atEndOfMonth().toString(), null,
                s.monthlyPoints(), s.projectedTotal().toPlainString(), false);
    }

    private record YearProjection(int year, BigDecimal yearToDate, BigDecimal projectedTotal,
                                  BigDecimal lastYearTotal, boolean lowConfidence) {}

    private YearProjection computeYearProjection(Long userId) {
        YearMonth current = YearMonth.now();
        LocalDate today = LocalDate.now();
        List<Transaction> txns =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        int year = today.getYear();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        BigDecimal ytd = expenseTotal(txns, yearStart, today);

        int daysElapsed = today.getDayOfYear();
        int daysInYear = today.lengthOfYear();
        BigDecimal dailyRate = ytd.divide(BigDecimal.valueOf(daysElapsed), 4,
                java.math.RoundingMode.HALF_EVEN);
        BigDecimal projected = ytd.add(dailyRate.multiply(
                        BigDecimal.valueOf(daysInYear - daysElapsed)))
                .setScale(2, java.math.RoundingMode.HALF_EVEN);

        BigDecimal lastYear = expenseTotal(txns, LocalDate.of(year - 1, 1, 1),
                LocalDate.of(year - 1, 12, 31));

        // January forecasts swing wildly on a few days of data — flag that
        return new YearProjection(year, ytd, projected, lastYear, daysElapsed < 7);
    }

    private String yearlyProjection(Long userId) {
        YearProjection y = computeYearProjection(userId);
        if (y.yearToDate().signum() == 0 && y.lastYearTotal().signum() == 0) {
            return "You haven't recorded any spending this year or last, so there's nothing to project.";
        }

        StringBuilder out = new StringBuilder(String.format(
                "Year-to-date you've spent $%.2f. At this pace you're projected to spend $%.2f in all of %d.",
                y.yearToDate(), y.projectedTotal(), y.year()));

        if (y.lowConfidence()) {
            out.append("\n(Note: it's very early in the year, so this forecast is low-confidence.)");
        }

        if (y.lastYearTotal().signum() > 0) {
            BigDecimal diff = y.projectedTotal().subtract(y.lastYearTotal());
            String word = diff.signum() > 0 ? "above" : diff.signum() < 0 ? "below" : "equal to";
            out.append(String.format(" That's %s last year's total of $%.2f.",
                    word.equals("equal to") ? "exactly" : word, y.lastYearTotal()));
        }

        out.append("\n\nSuggestions:");
        monthlyBudgetRepository.findByUser_UserIdAndBudgetMonth(userId, LocalDate.now())
                .map(MonthlyBudget::getBudgetAmount)
                .ifPresentOrElse(
                        b -> out.append(String.format(
                                "%n- Twelve months at your current pace costs $%.2f — that's %s your $%.2f monthly budget annualized ($%.2f).",
                                y.projectedTotal(),
                                y.projectedTotal().compareTo(b.multiply(BigDecimal.valueOf(12))) > 0
                                        ? "over" : "under",
                                b, b.multiply(BigDecimal.valueOf(12)))),
                        () -> out.append(String.format(
                                "%n- No monthly budget is set. At your pace, one around $%.0f/month (≈ $%.0f/year) would match reality.",
                                y.projectedTotal().divide(BigDecimal.valueOf(12), 0, java.math.RoundingMode.HALF_UP),
                                y.projectedTotal())));

        YearMonth biggest = null;
        BigDecimal biggestAmt = BigDecimal.ZERO;
        LocalDate jan1 = LocalDate.of(y.year(), 1, 1);
        List<Transaction> txns =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);
        for (LocalDate d = jan1; !d.isAfter(LocalDate.now()); d = d.plusMonths(1)) {
            BigDecimal amt = expenseTotal(txns, d, d.plusMonths(1).minusDays(1));
            if (amt.compareTo(biggestAmt) > 0) { biggestAmt = amt; biggest = YearMonth.from(d); }
        }
        if (biggest != null) {
            out.append(String.format(
                    "%n- Your heaviest month so far was %s at $%.2f; flattening that peak is where trimming helps most.",
                    biggest, biggestAmt));
        }
        return out.toString();
    }

    /** Monthly actuals for the calendar year, plus one projected-total bar. */
    private AnalysisResponse yearlyProjectionChart(Long userId, String formatted) {
        YearProjection y = computeYearProjection(userId);
        if (y.yearToDate().signum() == 0 && y.lastYearTotal().signum() == 0) return null;

        List<Transaction> txns =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);
        List<ChartPoint> points = new ArrayList<>();
        for (LocalDate m = LocalDate.of(y.year(), 1, 1); !m.isAfter(LocalDate.now()); m = m.plusMonths(1)) {
            points.add(new ChartPoint(YearMonth.from(m).toString(),
                    expenseTotal(txns, m, m.plusMonths(1).minusDays(1))
                            .setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()));
        }
        points.add(new ChartPoint(y.year() + " total (proj.)", y.projectedTotal().toPlainString()));
        return new AnalysisResponse("yearly_spending_projection", formatted, "bar",
                y.year() + "-01-01", y.year() + "-12-31", null,
                List.copyOf(points), y.projectedTotal().toPlainString(), false);
    }

    private String categoryProjection(Long userId, Map<String, String> p) {
        String rawType = p.get("type");
        String category = AiOrchestratorService.VALID_CATEGORIES.stream()
                .filter(v -> v.equalsIgnoreCase(rawType))
                .findFirst()
                .orElse(null);
        if (category == null) {
            return "Which category should I project? I can project Clothing, Entertainment, "
                    + "Transport, Groceries, Salary, Rent, or Other.";
        }

        YearMonth current = YearMonth.now();
        LocalDate today = LocalDate.now();
        List<Transaction> txns =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        // Month-to-date spend in this category
        BigDecimal monthToDate = txns.stream()
                .filter(t -> category.equals(t.getTransactionType())
                        && inRange(t, current.atDay(1), today))
                .map(Transaction::getTransactionAmount)
                .filter(a -> a != null && a.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Average monthly spend in this category over the last 3 completed months
        BigDecimal historySum = BigDecimal.ZERO;
        int monthsWithData = 0;
        for (int i = 1; i <= 3; i++) {
            YearMonth m = current.minusMonths(i);
            BigDecimal total = txns.stream()
                    .filter(t -> category.equals(t.getTransactionType())
                            && inRange(t, m.atDay(1), m.atEndOfMonth()))
                    .map(Transaction::getTransactionAmount)
                    .filter(a -> a != null && a.signum() < 0)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() > 0) { historySum = historySum.add(total); monthsWithData++; }
        }
        BigDecimal avg = monthsWithData == 0 ? BigDecimal.ZERO
                : historySum.divide(BigDecimal.valueOf(monthsWithData), 2, java.math.RoundingMode.HALF_EVEN);

        if (monthToDate.signum() == 0 && avg.signum() == 0) {
            return "You haven't recorded any " + category.toLowerCase(Locale.ROOT)
                    + " spending recently, so there's nothing to project.";
        }

        int daysElapsed = today.getDayOfMonth();
        int daysInMonth = current.lengthOfMonth();
        BigDecimal dailyRate = monthToDate.divide(BigDecimal.valueOf(Math.max(daysElapsed, 1)), 4,
                java.math.RoundingMode.HALF_EVEN);
        BigDecimal projected = monthToDate
                .add(dailyRate.multiply(BigDecimal.valueOf(daysInMonth - daysElapsed)))
                .setScale(2, java.math.RoundingMode.HALF_EVEN);

        StringBuilder out = new StringBuilder(String.format(
                "You've spent $%.2f on %s so far this month. At this pace you're projected to "
                        + "reach $%.2f by the end of %s.",
                monthToDate, category, projected, current));

        if (avg.signum() > 0) {
            BigDecimal diff = projected.subtract(avg);
            String word = diff.signum() > 0 ? "above" : diff.signum() < 0 ? "below" : "in line with";
            out.append(String.format(" That's %s your usual 3-month average for %s ($%.2f).",
                    word, category, avg));
            if (diff.signum() > 0 && monthToDate.signum() > 0) {
                out.append(String.format(
                        "%n%nSuggestions:%n- \" %s\" is pacing $%.2f above its average. Cutting roughly $%.2f a week "
                                + "over the remaining weeks would bring it back in line.",
                        category, diff,
                        diff.multiply(BigDecimal.valueOf(7))
                                .divide(BigDecimal.valueOf(Math.max(current.lengthOfMonth() - daysElapsed, 1)), 2,
                                        java.math.RoundingMode.HALF_EVEN)));
            }
        }

        // Tie into the monthly budget if one exists
        monthlyBudgetRepository.findByUser_UserIdAndBudgetMonth(userId, current.atDay(1))
                .map(MonthlyBudget::getBudgetAmount)
                .ifPresent(b -> {
                    // Category share estimate from history, as a rough per-category guide
                    if (avg.signum() > 0 && avg.compareTo(b) > 0) {
                        out.append(String.format(
                                "%n- Heads up: your %s average alone ($%.2f/month) exceeds your $%.2f "
                                        + "total monthly budget.",
                                category, avg, b));
                    }
                });

        return out.toString();
    }

    /** Chart: recent monthly totals for one category, plus the projected bar. */
    private AnalysisResponse categoryProjectionChart(Long userId, Map<String, String> p, String formatted) {
        String category = p.get("type"); // already normalised by the read handler, but be safe:
        if (category == null) return null;

        YearMonth current = YearMonth.now();
        LocalDate today = LocalDate.now();
        List<Transaction> txns =
                transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        List<ChartPoint> points = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            YearMonth m = current.minusMonths(i);
            points.add(new ChartPoint(m.toString(), categoryTotal(txns, category, m).toPlainString()));
        }
        points.add(new ChartPoint(current + " (proj.)", "0")); // replaced below once projected is known

        // Re-derive projection the same way as the read handler (kept simple for chart consistency)
        BigDecimal monthToDate = categoryTotal(txns, category, current);
        int daysElapsed = today.getDayOfMonth();
        BigDecimal dailyRate = monthToDate.divide(BigDecimal.valueOf(Math.max(daysElapsed, 1)), 4,
                java.math.RoundingMode.HALF_EVEN);
        BigDecimal projected = monthToDate
                .add(dailyRate.multiply(BigDecimal.valueOf(current.lengthOfMonth() - daysElapsed)))
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
        points.set(points.size() - 1, new ChartPoint(current + " (proj.)", projected.toPlainString()));

        return new AnalysisResponse("category_spending_projection", formatted, "bar",
                current.minusMonths(2).atDay(1).toString(), current.atEndOfMonth().toString(),
                category, List.copyOf(points), projected.toPlainString(), false);
    }

    /** Absolute expense total for one category in one month. */
    private BigDecimal categoryTotal(List<Transaction> txns, String category, YearMonth month) {
        return txns.stream()
                .filter(t -> category.equals(t.getTransactionType())
                        && t.getTransactionDate() != null
                        && YearMonth.from(t.getTransactionDate()).equals(month))
                .map(Transaction::getTransactionAmount)
                .filter(a -> a != null && a.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

