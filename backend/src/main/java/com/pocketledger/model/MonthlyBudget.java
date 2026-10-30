package com.pocketledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "monthly_budgets", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "budget_month"})
})
public class MonthlyBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "budget_id")
    private Long budgetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "budget_month", nullable = false)
    private LocalDate budgetMonth;

    @Column(name = "budget_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal budgetAmount;

    protected MonthlyBudget() {}

    public MonthlyBudget(User user, LocalDate budgetMonth, BigDecimal budgetAmount) {
        this.user = user;
        this.budgetMonth = budgetMonth;
        this.budgetAmount = budgetAmount;
    }

    public Long getBudgetId() { return budgetId; }
    public User getUser() { return user; }
    public LocalDate getBudgetMonth() { return budgetMonth; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }

    public void updateAmount(BigDecimal budgetAmount) {
        this.budgetAmount = budgetAmount;
    }
}
