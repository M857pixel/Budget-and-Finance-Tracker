package com.pocketledger.controller;

import com.pocketledger.model.MonthlyBudget;
import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.MonthlyBudgetRepository;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import com.pocketledger.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Direct-invocation unit tests. Calling controller.query(...) bypasses the servlet
 * filter chain entirely, so the Origin-check filter is never involved.
 */
class AiAssistantControllerTest {

    private TransactionRepository transactionRepository;
    private AiAssistantService aiAssistantService;
    private UserRepository userRepository;
    private AiOrchestratorService aiOrchestratorService;
    private AiPendingActionStore aiPendingActionStore;
    private CurrencyConversionService currencyConversionService;
    private MonthlyBudgetRepository monthlyBudgetRepository;

    private AiAssistantController controller;
    private final User user = new User("test@example.com", "hash");

    // ---------- helpers ----------

    private static Transaction txn(String name, String type, String amount, String date, String desc) {
        return new Transaction(null, name, type, new BigDecimal(amount), LocalDate.parse(date), desc);
    }

    private MockHttpServletRequest requestWithSession(long userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        req.setSession(session);
        return req;
    }

    private MockHttpServletRequest requestWithoutSession() {
        return new MockHttpServletRequest();
    }

    private ResponseEntity<?> query(String rawQuery, MockHttpServletRequest req) {
        return controller.query(new AiAssistantController.QueryRequest(rawQuery), req);
    }

    private AiAssistantController.AssistantResponse asAssistant(ResponseEntity<?> resp) {
        return (AiAssistantController.AssistantResponse) resp.getBody();
    }

    private AiAssistantController.AnalysisResponse asAnalysis(ResponseEntity<?> resp) {
        return (AiAssistantController.AnalysisResponse) resp.getBody();
    }

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        aiAssistantService = mock(AiAssistantService.class);
        userRepository = mock(UserRepository.class);
        aiOrchestratorService = mock(AiOrchestratorService.class);
        aiPendingActionStore = mock(AiPendingActionStore.class);
        currencyConversionService = mock(CurrencyConversionService.class);
        monthlyBudgetRepository = mock(MonthlyBudgetRepository.class);
        controller = new AiAssistantController(transactionRepository, aiAssistantService,
                userRepository, aiOrchestratorService, aiPendingActionStore,
                currencyConversionService, monthlyBudgetRepository);
    }

    // ---------- authentication & validation ----------

    @Test
    void rejectsRequestWithoutSession() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(anyLong()))
                .thenReturn(List.of());
        when(aiAssistantService.classify(anyString(), any()))
                .thenReturn("[{\"action\":\"NONE\"}]");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query("hello", requestWithoutSession()));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        assertEquals("Please log in.", e.getReason());
    }

    @Test
    void authCheckRunsBeforeBlankQueryCheck() {
        // No session + blank query -> 401, not 400 (requireUserId runs first)
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query("   ", requestWithoutSession()));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verifyNoInteractions(aiAssistantService);
    }

    @Test
    void rejectsNonLongUserIdInSession() {
        MockHttpServletRequest req = requestWithoutSession();
        ((MockHttpSession) req.getSession(true)).setAttribute("userId", "not-a-long");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query("hello", req));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertEquals("Invalid user ID in session.", e.getReason());
    }

    @Test
    void rejectsBlankQueryWithValidSession() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query("   ", requestWithSession(1L)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("A query is required.", e.getReason());
        verifyNoInteractions(aiAssistantService);
    }

    @Test
    void rejectsNullQueryBody() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query(null, requestWithSession(1L)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    // ---------- service failure ----------

    @Test
    void returns503WhenClassifierFails() {
        when(aiAssistantService.classify(anyString(), any()))
                .thenThrow(new IllegalStateException("no key"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> query("hello", requestWithSession(1L)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    // ---------- NONE / confirm / cancel flows ----------

    @Test
    void unrelatedQueryReturnsNONE() {
        when(aiAssistantService.classify(eq("hello"), isNull()))
                .thenReturn("[{\"action\":\"NONE\"}]");

        ResponseEntity<?> resp = query("hello", requestWithSession(1L));
        assertEquals(200, resp.getStatusCode().value());
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("NONE", body.action());
        assertEquals("I can only help with questions about your own transactions.",
                body.result());
    }

    @Test
    void confirmWithNothingPendingReturnsNONEMessage() {
        when(aiAssistantService.classify(eq("yes"), isNull()))
                .thenReturn("[{\"action\":\"CONFIRM_PENDING\"}]");
        when(aiPendingActionStore.consume(1L)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = query("yes", requestWithSession(1L));
        assertEquals(200, resp.getStatusCode().value());
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("NONE", body.action());
        assertEquals("There's nothing waiting for confirmation right now.", body.result());
    }

    @Test
    void confirmExecutesStoredPendingAction() {
        AiActionClassification pending = new AiActionClassification(
                "CREATE_TRANSACTION",
                Map.of("name", "Coffee", "type", "Groceries",
                        "amount", "-4.50", "date", "2026-11-01"));
        when(aiPendingActionStore.peek(1L)).thenReturn(Optional.of(pending));
        when(aiPendingActionStore.consume(1L)).thenReturn(Optional.of(pending));
        when(aiAssistantService.classify(eq("yes"),
                argThat(desc -> desc != null && desc.contains("CREATE_TRANSACTION"))))
                .thenReturn("[{\"action\":\"CONFIRM_PENDING\"}]");
        when(aiOrchestratorService.executeComplexOperation(pending, 1L))
                .thenReturn("Added \"Coffee\" (Groceries) for $-4.50 on 2026-11-01.");

        ResponseEntity<?> resp = query("yes", requestWithSession(1L));
        assertEquals(200, resp.getStatusCode().value());
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("CREATE_TRANSACTION", body.action());
        assertEquals("Added \"Coffee\" (Groceries) for $-4.50 on 2026-11-01.", body.result());

        verify(aiPendingActionStore).consume(1L);
        verify(aiOrchestratorService).executeComplexOperation(pending, 1L);
    }

    @Test
    void confirmRevalidatesAndKeepsPendingWhenInvalid() {
        AiActionClassification pending = new AiActionClassification(
                "CREATE_TRANSACTION", Map.of("name", "Coffee")); // missing amount
        when(aiPendingActionStore.peek(1L)).thenReturn(Optional.of(pending));
        when(aiPendingActionStore.consume(1L)).thenReturn(Optional.of(pending));
        when(aiAssistantService.classify(anyString(), anyString()))
                .thenReturn("[{\"action\":\"CONFIRM_PENDING\"}]");

        ResponseEntity<?> resp = query("yes", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("CLARIFICATION_REQUIRED", body.action());
        assertEquals("I need a non-zero amount. How much was this transaction for?",
                body.result());

        verify(aiPendingActionStore).put(eq(1L), eq(pending));
        verifyNoInteractions(aiOrchestratorService);
    }

    @Test
    void cancelClearsPendingAction() {
        when(aiAssistantService.classify(eq("cancel"), isNull()))
                .thenReturn("[{\"action\":\"CANCEL_PENDING\"}]");

        ResponseEntity<?> resp = query("cancel", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("CANCEL_PENDING", body.action());
        assertEquals("No problem — I've cancelled that.", body.result());
        verify(aiPendingActionStore).clear(1L);
    }

    @Test
    void pendingContextIsPassedToClassifier() {
        AiActionClassification pending = new AiActionClassification(
                "DELETE_TRANSACTION", Map.of("keyword", "spotify"));
        when(aiPendingActionStore.peek(1L)).thenReturn(Optional.of(pending));
        when(aiAssistantService.classify(anyString(), isNotNull()))
                .thenReturn("[{\"action\":\"CANCEL_PENDING\"}]");

        query("actually, something else", requestWithSession(1L));
        verify(aiAssistantService).classify(anyString(), isNotNull());
    }

    // ---------- write actions: confirmation flow ----------

    @Test
    void validWriteActionStoresPendingAndAsksForConfirmation() {
        when(aiAssistantService.classify(eq("add coffee 4.50"), isNull()))
                .thenReturn("[{\"action\":\"CREATE_TRANSACTION\",\"parameters\":"
                        + "{\"name\":\"Coffee\",\"type\":\"Groceries\","
                        + "\"amount\":\"-4.50\",\"date\":\"2026-11-16\"}}]");

        ResponseEntity<?> resp = query("add coffee 4.50", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("CONFIRMATION_REQUIRED", body.action());
        assertEquals("I'll add a Groceries called \"Coffee\" for $-4.50 dated 2026-11-16. "
                + "Shall I go ahead?", body.result());

        verify(aiPendingActionStore).put(eq(1L), any(AiActionClassification.class));
        verifyNoInteractions(aiOrchestratorService);
    }

    @Test
    void invalidWriteActionStaysPendingAndAsksClarification() {
        when(aiAssistantService.classify(eq("add coffee"), isNull()))
                .thenReturn("[{\"action\":\"CREATE_TRANSACTION\",\"parameters\":"
                        + "{\"name\":\"Coffee\",\"type\":\"Groceries\"}}]"); // no amount

        ResponseEntity<?> resp = query("add coffee", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("CLARIFICATION_REQUIRED", body.action());
        assertEquals("I need a non-zero amount. How much was this transaction for?",
                body.result());
        verify(aiPendingActionStore).put(eq(1L), any(AiActionClassification.class));
    }

    @Test
    void deleteWithoutIdentifiersAsksClarification() {
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"DELETE_TRANSACTION\",\"parameters\":{}}]");

        ResponseEntity<?> resp = query("delete something", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("CLARIFICATION_REQUIRED", body.action());
        assertEquals("I need more information to identify which transaction to delete. "
                + "Please specify a name, category, or date.", body.result());
    }

    @Test
    void bulkUpdateMissingNewTypeAsksClarification() {
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"UPDATE_TRANSACTION_TYPE_BULK\","
                        + "\"parameters\":{\"search_criteria\":\"spotify\"}}]");

        ResponseEntity<?> resp = query("recategorize my spotify payments",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("CLARIFICATION_REQUIRED", body.action());
        assertEquals("What category should they be moved to?", body.result());
    }

    @Test
    void updateSingleWithoutUpdateFieldAsksClarification() {
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"UPDATE_SINGLE_TRANSACTION\","
                        + "\"parameters\":{\"keyword\":\"spotify\"}}]");

        ResponseEntity<?> resp = query("change my spotify payment", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("CLARIFICATION_REQUIRED", body.action());
        assertEquals("What would you like to change about this transaction?",
                body.result());
    }

    // ---------- read actions ----------

    @Test
    void mostRecentTransactionPrintedInFull() {
        Transaction t = txn("Paycheck", "Salary", "1000.00", "2026-11-04", "Paycheck deposit");
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(t));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_MOST_RECENT_TRANSACTION\"}]");

        ResponseEntity<?> resp = query("what was my last transaction",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("GET_MOST_RECENT_TRANSACTION", body.action());
        assertEquals("Your most recent transaction was \"Paycheck\" (Salary) "
                        + "for $1000.00 on 2026-11-04\n   Paycheck deposit.",
                body.result());
    }

    @Test
    void mostRecentTransactionWithNoHistoryReturnsFriendlyMessage() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_MOST_RECENT_TRANSACTION\"}]");

        ResponseEntity<?> resp = query("what was my last transaction",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("You haven't recorded any transactions yet.", body.result());
    }

    @Test
    void recentTransactionsListLimitedToAvailableTransactions() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(
                        txn("A", "Groceries", "-1.50", "2026-11-10", null),
                        txn("B", "Rent", "-1200.00", "2026-11-09", null)));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"RECENT_TRANSACTIONS_LIST\","
                        + "\"parameters\":{\"count\":\"999\"}}]"); // clamps to 50

        ResponseEntity<?> resp = query("show my last 999 transactions",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("RECENT_TRANSACTIONS_LIST", body.action());
        assertTrue(body.result().startsWith("Here are your recent transactions:\n\n"
                + "Your 2 most recent transactions:"));
    }

    @Test
    void spendingTotalWithExpensesReturnsChartAnalysis() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(
                        txn("A", "Groceries", "-40.00", LocalDate.now().toString(), null),
                        txn("B", "Salary", "1000.00", LocalDate.now().toString(), null)));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_SPENDING_TOTAL\"}]");

        ResponseEntity<?> resp = query("how much did I spend", requestWithSession(1L));
        assertEquals(200, resp.getStatusCode().value());
        AiAssistantController.AnalysisResponse body = asAnalysis(resp);
        assertEquals("monthly_expenses", body.action());
        assertEquals("bar", body.chart());
        assertEquals("40.00", body.total());
        assertEquals(1, body.data().size());
        assertFalse(body.empty());
    }

    @Test
    void spendingTotalWithNoSpendingReturnsPlainTextResponse() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_SPENDING_TOTAL\"}]");

        ResponseEntity<?> resp = query("how much did I spend", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("GET_SPENDING_TOTAL", body.action());
        assertEquals("You haven't recorded any spending in total.", body.result());
    }

    @Test
    void transactionSummaryWithExpensesReturnsDonutAnalysis() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(
                        txn("Coffee", "Groceries", "-4.50",
                                LocalDate.now().toString(), null),
                        txn("Bus", "Transport", "-2.75",
                                LocalDate.now().toString(), null)));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_TRANSACTION_SUMMARY\"}]");

        ResponseEntity<?> resp = query("break down my spending", requestWithSession(1L));
        AiAssistantController.AnalysisResponse body = asAnalysis(resp);
        assertEquals("spending_by_category", body.action());
        assertEquals("donut", body.chart());
        assertEquals("7.25", body.total());
    }

    @Test
    void currentBalanceWithNoTransactionsIsFormattedZeroBalance() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CURRENT_BALANCE_QUERY\"}]");

        ResponseEntity<?> resp = query("whats my balance", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("Your current balance is $0.00.", body.result());
    }

    @Test
    void currentBalanceWithPositiveNetReportsBalance() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(
                        txn("Pay", "Salary", "1500.00", LocalDate.now().toString(), null),
                        txn("Stuff", "Groceries", "-100.00",
                                LocalDate.now().toString(), null)));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CURRENT_BALANCE_QUERY\"}]");

        ResponseEntity<?> resp = query("whats my balance", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("Your current account balance is $1400.00.", body.result());
    }

    @Test
    void convertCurrencyDelegatesToConversionService() {
        when(currencyConversionService.formatConversion(
                new BigDecimal("50"), "USD", "EUR"))
                .thenReturn("50.00 USD = 45.92 EUR (1 USD = 0.9184 EUR)");
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CONVERT_CURRENCY\",\"parameters\":"
                        + "{\"amount\":\"50\",\"from_currency\":\"USD\","
                        + "\"to_currency\":\"EUR\"}}]");

        ResponseEntity<?> resp = query("50 USD to EUR", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("CONVERT_CURRENCY", body.action());
        assertEquals("50.00 USD = 45.92 EUR (1 USD = 0.9184 EUR)", body.result());
    }

    @Test
    void convertCurrencyFailureFallsBackToFriendlyMessage() {
        when(currencyConversionService.formatConversion(any(), any(), any()))
                .thenThrow(new CurrencyConversionService.CurrencyConversionException("boom"));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CONVERT_CURRENCY\",\"parameters\":"
                        + "{\"amount\":\"50\",\"from_currency\":\"USD\","
                        + "\"to_currency\":\"EUR\"}}]");

        ResponseEntity<?> resp = query("50 USD to EUR", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("I couldn't perform that conversion right now.", body.result());
    }

    @Test
    void convertCurrencyMissingParamsRequiresMoreInfo() {
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CONVERT_CURRENCY\",\"parameters\":"
                        + "{\"amount\":\"50\"}}]");

        ResponseEntity<?> resp = query("convert something", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("I need an amount and both currencies to convert.", body.result());
    }

    @Test
    void monthlyBudgetFoundForRequestedMonth() {
        MonthlyBudget budget = new MonthlyBudget(user,
                LocalDate.of(2026, 10, 1), new BigDecimal("250.00"));
        when(monthlyBudgetRepository.findByUser_UserIdAndBudgetMonth(
                1L, LocalDate.of(2026, 10, 1))).thenReturn(Optional.of(budget));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_MONTHLY_BUDGET\","
                        + "\"parameters\":{\"month\":\"2026-10\"}}]");

        ResponseEntity<?> resp = query("whats my budget for october",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("GET_MONTHLY_BUDGET", body.action());
        assertEquals("Your budget for 2026-10 is $250.00.", body.result());
    }

    @Test
    void monthlyBudgetNotSetReturnsNotice() {
        when(monthlyBudgetRepository.findByUser_UserIdAndBudgetMonth(
                eq(1L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"GET_MONTHLY_BUDGET\"}]");

        ResponseEntity<?> resp = query("whats my budget", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertTrue(body.result().startsWith("You haven't set a budget for"));
    }

    @Test
    void compareSpendingComparesTwoPeriods() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(
                        txn("a", "Groceries", "-100.00", "2026-10-05", null),
                        txn("b", "Groceries", "-50.00", "2026-09-05", null)));
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"COMPARE_SPENDING\",\"parameters\":{"
                        + "\"start_date_a\":\"2026-10-01\",\"end_date_a\":\"2026-10-31\","
                        + "\"start_date_b\":\"2026-09-01\",\"end_date_b\":\"2026-09-30\"}}]");

        ResponseEntity<?> resp = query("did I spend more this month than last",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("COMPARE_SPENDING", body.action());
        assertTrue(body.result().contains(
                "You spent $50.00 more between 2026-10-01 and 2026-10-31"));
    }

    // ---------- multi-action & parse resilience ----------

    @Test
    void multipleActionsReturnCombinedReplyMarkedMULTI() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("[{\"action\":\"CURRENT_BALANCE_QUERY\"},"
                        + "{\"action\":\"GET_MOST_RECENT_TRANSACTION\"}]");

        ResponseEntity<?> resp = query("balance and last transaction please",
                requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("MULTI", body.action());
        assertTrue(body.result().contains("Your current balance is $0.00."));
        assertTrue(body.result().contains(
                "You haven't recorded any transactions yet."));
    }

    @Test
    void malformedClassifierOutputFallsBackToUppercasedAction() {
        when(aiAssistantService.classify(anyString(), isNull()))
                .thenReturn("not-json");

        ResponseEntity<?> resp = query("whatever", requestWithSession(1L));
        AiAssistantController.AssistantResponse body = asAssistant(resp);
        assertEquals("NOT-JSON", body.action());
        assertEquals("Unknown action: NOT-JSON", body.result());
    }
}