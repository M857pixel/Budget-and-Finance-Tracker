package com.pocketledger.service;

import com.pocketledger.repository.*;
import com.pocketledger.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.pocketledger.service.AiInputMinimizer.*;

class FinancialAnalysisServiceTest {
    final TransactionRepository transactions=mock(TransactionRepository.class);
    final MonthlyBudgetRepository budgets=mock(MonthlyBudgetRepository.class);
    final FinancialAnalysisService service=new FinancialAnalysisService(transactions,budgets);
    final LocalDate today=LocalDate.of(2026,11,9);
    TransactionRepository.AnalysisTotal row(int month,String category,String expense,String income) {
        var row=mock(TransactionRepository.AnalysisTotal.class);
        when(row.getYear()).thenReturn(2026); when(row.getMonth()).thenReturn(month);
        when(row.getCategory()).thenReturn(category); when(row.getExpense()).thenReturn(new BigDecimal(expense)); when(row.getIncome()).thenReturn(new BigDecimal(income));
        return row;
    }
    @Test void categoryAndDecimalTotalsNeverIncludeIncomeOrOtherCategories() {
        doReturn(List.of(row(11,"Groceries","0.30","10.00"),row(11,"Rent","999.00","0.00"))).when(transactions).analysisTotals(eq(7L),any(),any());
        var result=service.analyze(7L,new Selection(AnalysisIntent.spending_by_category,Period.THIS_MONTH,Category.Groceries,Chart.donut),today);
        assertEquals(new BigDecimal("0.30"),result.total()); assertEquals(List.of(new FinancialAnalysisService.Point("Groceries",new BigDecimal("0.30"))),result.data());
        assertFalse(result.result().contains("999"));
        verify(transactions).analysisTotals(7L,LocalDate.of(2026,11,1),today);
        verifyNoInteractions(budgets);
    }
    @Test void incomeTrendZeroFillsMonthsAndNeverIncludesExpenses() {
        doReturn(List.of(row(11,"Salary","9000.00","100.05"))).when(transactions).analysisTotals(eq(7L),any(),any());
        var result=service.analyze(7L,new Selection(AnalysisIntent.income_trend,Period.LAST_6_MONTHS,Category.ALL,Chart.line),today);
        assertEquals(new BigDecimal("100.05"),result.total()); assertEquals(6,result.data().size());
        assertEquals("2026-06",result.data().getFirst().label());
        assertEquals(new BigDecimal("0.00"),result.data().getFirst().amount());
        assertEquals(new BigDecimal("100.05"),result.data().getLast().amount());
    }
    @Test void budgetUsesOwnersBudgetAndReportsOverspending() {
        doReturn(List.of(row(11,"Rent","100.25","5000.00"))).when(transactions).analysisTotals(eq(7L),any(),any());
        when(budgets.findByUser_UserIdAndBudgetMonth(7L,today.withDayOfMonth(1))).thenReturn(Optional.of(new MonthlyBudget(mock(User.class),today.withDayOfMonth(1),new BigDecimal("100.00"))));
        var result=service.analyze(7L,new Selection(AnalysisIntent.budget_status,Period.THIS_MONTH,Category.ALL,Chart.bar),today);
        assertTrue(result.result().contains("0.25 over budget"));
        assertEquals(2,result.data().size()); assertEquals(new BigDecimal("100.25"),result.total());
        verify(budgets).findByUser_UserIdAndBudgetMonth(7L,today.withDayOfMonth(1));
    }
    @Test void missingBudgetAndEmptyDataAreExplicitNotInvented() {
        doReturn(List.of()).when(transactions).analysisTotals(eq(7L),any(),any());
        when(budgets.findByUser_UserIdAndBudgetMonth(anyLong(),any())).thenReturn(Optional.empty());
        var result=service.analyze(7L,new Selection(AnalysisIntent.budget_status,Period.THIS_MONTH,Category.ALL,Chart.bar),today);
        assertTrue(result.empty()); assertTrue(result.result().contains("No budget is set for 1 month"));
        assertEquals(new BigDecimal("0.00"),result.total());
    }
    @Test void unknownStoredCategoriesCannotLeakArbitraryLabels() {
        doReturn(List.of(row(11,"private merchant secret","20.00","0.00"))).when(transactions).analysisTotals(eq(7L),any(),any());
        var result=service.analyze(7L,new Selection(AnalysisIntent.spending_by_category,Period.THIS_MONTH,Category.ALL,Chart.donut),today);
        assertEquals("Other",result.data().getFirst().label()); assertFalse(result.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class,() -> service.analyze(null,new Selection(AnalysisIntent.monthly_expenses,Period.THIS_MONTH,Category.ALL,Chart.bar),today));
    }
}
