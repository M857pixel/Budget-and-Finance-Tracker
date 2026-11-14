package com.pocketledger.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.math.BigDecimal;
import com.pocketledger.repository.*;
import com.pocketledger.model.*;
import static com.pocketledger.service.AiInputMinimizer.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamedMonthAnalysisTest {
    final AiInputMinimizer parser=new AiInputMinimizer();
    final LocalDate today=LocalDate.of(2026,11,11);
    @Test void octoberBudgetIsHandledLocally() {
        var prepared=parser.prepare("can you show me my october budget",null,today);
        assertTrue(prepared.supported(), "A named-month budget must use the local financial path");
        assertEquals(AnalysisIntent.budget_status,prepared.selection().intent());
        assertEquals(YearMonth.of(2026,10),prepared.selection().selectedMonth());
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)},range(prepared.selection(),today));
    }
    @Test void explicitYearAndLeapYearUseTheFullRequestedMonth() {
        var selection=parser.prepare("show my budget for February 2024",null,today).selection();
        assertEquals(YearMonth.of(2024,2),selection.selectedMonth());
        assertArrayEquals(new LocalDate[]{LocalDate.of(2024,2,1),LocalDate.of(2024,2,29)},range(selection,today));
    }
    @Test void noYearUsesMostRecentMonthAndFollowupsPreserveOrReplaceSelection() {
        var selected=parser.prepare("show my December budget",null,today).selection();
        assertEquals(YearMonth.of(2025,12),selected.selectedMonth());
        assertEquals(selected.selectedMonth(),parser.prepare("same as a line chart",selected,today).selection().selectedMonth());
        assertEquals(YearMonth.of(2026,10),parser.prepare("what about October",selected,today).selection().selectedMonth());
        assertEquals(Period.THIS_MONTH,parser.prepare("what about this month",selected,today).selection().period());
        assertEquals(YearMonth.of(2025,10),parser.prepare("my October budget last year",null,today).selection().selectedMonth());
    }
    @Test void unsupportedOrAmbiguousDatesAreNotSilentlyReinterpreted() {
        for(String request:List.of("my budget between October and November","my October and November budget","my October budget this month","my budget for 2025"))
            assertFalse(parser.prepare(request,null,today).supported(),request);
        assertEquals(Period.THIS_MONTH,parser.prepare("may I see my budget",null,today).selection().period());
    }
    @Test void budgetLookupUsesOwnerAndOctoberOnlyWithoutAnyExternalAdapter() {
        var transactions=mock(TransactionRepository.class);
        var budgets=mock(MonthlyBudgetRepository.class);
        var selection=parser.prepare("can you show me my october budget",null,today).selection();
        when(transactions.analysisTotals(7L,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31))).thenReturn(List.of());
        when(budgets.findByUser_UserIdAndBudgetMonth(7L,LocalDate.of(2026,10,1)))
            .thenReturn(Optional.of(new MonthlyBudget(mock(User.class),LocalDate.of(2026,10,1),new BigDecimal("123.45"))));
        var result=new FinancialAnalysisService(transactions,budgets).analyze(7L,selection,today);
        assertEquals("2026-10-01",result.startDate());assertEquals("2026-10-31",result.endDate());
        assertTrue(result.result().contains("123.45"));
        verify(budgets).findByUser_UserIdAndBudgetMonth(7L,LocalDate.of(2026,10,1));
        verifyNoMoreInteractions(budgets);
    }
}
