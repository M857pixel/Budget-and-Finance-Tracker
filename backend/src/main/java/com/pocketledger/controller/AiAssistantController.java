/*
    Controller for the AI that provides list of possible methods for the AI to run based on user input.



 */


package com.pocketledger.controller;

import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.service.AiAssistantService;
import com.pocketledger.model.User;
import com.pocketledger.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
// Mirror CORS settings from LedgerController for frontend compatibility
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class AiAssistantController {

    private final TransactionRepository transactionRepository;
    private final AiAssistantService aiAssistantService;
    private final UserRepository userRepository;


    public AiAssistantController(TransactionRepository transactionRepository, AiAssistantService aiAssistantService, UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.aiAssistantService = aiAssistantService;
        this.userRepository = userRepository;
    }

    public record QueryRequest(String query) {}
    public record AssistantResponse(String action, String result) {}

    @PostMapping("/query")
    public ResponseEntity<AssistantResponse> query(@RequestBody QueryRequest body, HttpServletRequest request) {
        Long userId = requireUserId(request);

        if (body == null || body.query() == null || body.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query is required.");
        }

        String action;
        try {
            action = aiAssistantService.classify(body.query());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is unavailable right now.", e);
        }

        String result = switch (action) {
            case "GET_MOST_RECENT_TRANSACTION_CATEGORY" -> MostRecentTransactionCategory(userId);
            case "GET_TRANSACTION_SUMMARY" -> getTransactionSummary(userId);
            case "CURRENT_BALANCE_QUERY" -> currentBalanceQuery(userId);
            case "RECENT_TRANSACTIONS_LIST" -> {yield recentTransactionsList(userId, 5);} // Default to 5
            case "NONE" -> "I can only help with questions about your transactions.";
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unsupported assistant action: " + action);
        };

        return ResponseEntity.ok(new AssistantResponse(action, result));
    }

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



    private String MostRecentTransactionCategory(Long userId) {
        List<Transaction> transactions = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        if (transactions.isEmpty()) {
            return "You have no transactions yet.";
        }

        String category = transactions.get(0).getTransactionType();
        if (category != null) {
            return category;
        } else {
            return "Uncategorized";
        }
    }

    /**
     * Aggregates transaction totals by type for given user.
     * @Returns a summary string showing counts and totals per transaction type.
     */
    private String getTransactionSummary(Long userId) {
        List<Transaction> transactions = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);

        if (transactions.isEmpty()) {
            return "You have no transactions to summarize.";
        }

        Map<String, BigDecimal> totalByType = new HashMap<>();
        Map<String, Integer> countByType = new HashMap<>();

        for (Transaction transaction : transactions) {
            String type = transaction.getTransactionType();
            if (type == null || type.isBlank()) {
                type = "Uncategorized";
            }

            countByType.merge(type, 1, Integer::sum);
            totalByType.merge(type, transaction.getTransactionAmount(), BigDecimal::add);
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Transaction Summary:\n");

        for (String type : totalByType.keySet()) {
            summary.append(String.format("- %s: %d transactions, totaling $%.2f\n", type, countByType.get(type), totalByType.get(type)));
        }

        BigDecimal grandTotal = totalByType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        summary.append(String.format("Total: $%.2f across %d transactions", grandTotal, transactions.size()));

        return summary.toString();
    }

    /**
     * Retrieves the current balance for the authenticated user.
     */
    private String currentBalanceQuery(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        BigDecimal balance = user.getBalance();

        if (balance == null) {
            return "Your current balance is $0";
        }

        return String.format("Your current balance is $%s", balance.toString());
    }


    /**
     * Returns the most recent transactions for the given user.
     * @param limit Maximum number of transactions to return
     */
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

