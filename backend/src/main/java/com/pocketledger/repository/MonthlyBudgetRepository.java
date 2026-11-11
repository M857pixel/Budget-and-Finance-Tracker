package com.pocketledger.repository;

import com.pocketledger.model.MonthlyBudget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MonthlyBudgetRepository extends JpaRepository<MonthlyBudget, Long> {
    List<MonthlyBudget> findByUser_UserIdOrderByBudgetMonthDesc(Long userId);
    Optional<MonthlyBudget> findByUser_UserIdAndBudgetMonth(Long userId, LocalDate budgetMonth);
}
