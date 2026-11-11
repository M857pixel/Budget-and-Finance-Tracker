/*
   Maps to the monthly_budgets table using JPA.
   Represents a single row in the monthly_budgets table.

   @Entity tells JPA this class represents a database.
   @Table connects to table created in migration by name.
   @Id marks the primary key.
   @ManyToOne  tells many transactions can belong to one user.
   @JoinColumn maps that relationship to the foreign key column in migration.
   @Column annotation connects java fields to Supabase column names.
   @GeneratedValue tells JPA it is a generated value when row is created.
 */

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

    //JPA needs a no argument constructor to create users when reading rows
    protected MonthlyBudget() {}

    //Constructor to create monthly budget before saving
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
