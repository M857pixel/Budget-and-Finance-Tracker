package com.pocketledger.controller;

import com.pocketledger.model.MonthlyBudget;
import com.pocketledger.repository.MonthlyBudgetRepository;
import com.pocketledger.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/users/{userId}/budget")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class MonthlyBudgetController {
    private final MonthlyBudgetRepository budgets;
    private final UserRepository users;

    public MonthlyBudgetController(MonthlyBudgetRepository budgets, UserRepository users) {
        this.budgets = budgets;
        this.users = users;
    }

    @GetMapping
    public BigDecimal getBudget(@PathVariable Long userId, @RequestParam YearMonth month,
                                HttpServletRequest request) {
        requireUser(userId, request);
        return budgets.findByUser_UserIdAndBudgetMonth(userId, month.atDay(1))
                .map(MonthlyBudget::getBudgetAmount).orElse(BigDecimal.ZERO);
    }

    @PutMapping
    @Transactional
    public BigDecimal saveBudget(@PathVariable Long userId, @RequestParam YearMonth month,
                                 @RequestBody BudgetRequest body, HttpServletRequest request) {
        requireUser(userId, request);
        BigDecimal amount = body.amount();
        if (amount == null || amount.signum() < 0 || amount.compareTo(new BigDecimal("9999999999.99")) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a nonnegative budget with at most two decimal places.");
        }
        var existing = budgets.findByUser_UserIdAndBudgetMonth(userId, month.atDay(1));
        if (existing.isPresent()) {
            var budget = existing.get();
            if (budget.getBudgetAmount().compareTo(amount) != 0) {
                budget.updateAmount(amount);
                budgets.save(budget);
            }
        } else if (amount.signum() != 0) {
            var user = users.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
            budgets.save(new MonthlyBudget(user, month.atDay(1), amount));
        }
        return amount;
    }

    private static void requireUser(Long userId, HttpServletRequest request) {
        var session = request.getSession(false);
        var authenticatedId = session == null ? null : session.getAttribute("userId");
        if (authenticatedId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        if (!userId.equals(authenticatedId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account does not own this budget.");
    }

    public record BudgetRequest(BigDecimal amount) {}
}
