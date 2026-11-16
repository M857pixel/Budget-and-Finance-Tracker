package com.pocketledger.service;

import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiOrchestratorServiceTest {

    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private AiOrchestratorService service;
    private final User user = new User("test@example.com", "hash");

    /** Real Transaction entity — no Mockito involvement, so no nested-stubbing issues. */
    private static Transaction txn(String name, String type, String amount,
                                   String date, String description) {
        return new Transaction(null, name, type,
                new BigDecimal(amount), LocalDate.parse(date), description);
    }

    private static Map<String, String> params(Object... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (String) kv[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AiOrchestratorService(transactionRepository, userRepository);
    }

    // ---------- dispatch ----------

    @Test
    void executeComplexOperationRejectsUnknownAction() {
        AiActionClassification c = new AiActionClassification("BOGUS_ACTION", Map.of());
        assertThrows(IllegalStateException.class,
                () -> service.executeComplexOperation(c, 1L));
    }

    // ---------- CREATE_TRANSACTION ----------

    @Test
    void createTransactionPersistsAndReportsSavedValues() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Transaction saved = txn("Coffee", "Groceries", "-4.50", "2026-11-16", null);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(saved);

        String result = service.executeComplexOperation(new AiActionClassification(
                "CREATE_TRANSACTION",
                params("name", "Coffee", "type", "groceries",
                        "amount", "-4.50", "date", "2026-11-16")), 1L);

        assertTrue(result.startsWith("Added \"Coffee\" (Groceries)"));
        assertTrue(result.contains("$-4.50"));
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void createTransactionNormalisesInvalidCategoryToOther() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Transaction saved = txn("Thing", "Other", "-1.00", "2026-11-16", null);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(saved);

        String result = service.executeComplexOperation(new AiActionClassification(
                "CREATE_TRANSACTION",
                params("name", "Thing", "type", "Nonsense", "amount", "-1.00")), 1L);

        assertTrue(result.contains("(Other)"));
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void createTransactionRejectsUnparseableAmount() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        String result = service.executeComplexOperation(new AiActionClassification(
                "CREATE_TRANSACTION", params("name", "X", "amount", "abc")), 1L);

        assertEquals("I couldn't read an amount from that request, so nothing was added.",
                result);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransactionFailsWhenUserMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.executeComplexOperation(new AiActionClassification(
                        "CREATE_TRANSACTION", params("name", "X", "amount", "1.00")), 99L));
        verify(transactionRepository, never()).save(any());
    }

    // ---------- UPDATE_TRANSACTION_TYPE_BULK ----------

    @Test
    void bulkUpdateRecategorisesAllNameMatches() {
        Transaction a = txn("Spotify", "Entertainment", "-10.00", "2026-11-01", null);
        Transaction b = txn("Spotify Premium", "Other", "-12.00", "2026-11-02", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "spotify"))
                .thenReturn(List.of(a, b));

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_TRANSACTION_TYPE_BULK",
                params("search_criteria", "spotify", "new_type", "groceries")), 1L);

        assertTrue(result.startsWith("Updated 2 transactions from"));
        // state assertion on real entities — the strongest form of verification
        assertEquals("Groceries", a.getTransactionType());
        assertEquals("Groceries", b.getTransactionType());
        // names/amounts/dates untouched by the nulls passed to updateDetails
        assertEquals("Spotify", a.getTransactionName());
        assertEquals(new BigDecimal("-10.00"), a.getTransactionAmount());
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void bulkUpdateFallsBackToDescriptionSearch() {
        Transaction t = txn("Utility", "Other", "-90.00", "2026-11-01", "electricity bill");
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "bill"))
                .thenReturn(List.of());
        when(transactionRepository.findByUser_UserIdAndTransactionDescriptionContaining(1L, "bill"))
                .thenReturn(List.of(t));

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_TRANSACTION_TYPE_BULK",
                params("search_criteria", "bill", "new_type", "Rent")), 1L);

        assertTrue(result.startsWith("Updated 1 transaction"));
        assertEquals("Rent", t.getTransactionType());
        verify(transactionRepository).save(t);
    }

    @Test
    void bulkUpdateWithNoMatchesReportsNotFound() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "zzz"))
                .thenReturn(List.of());
        when(transactionRepository.findByUser_UserIdAndTransactionDescriptionContaining(1L, "zzz"))
                .thenReturn(List.of());

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_TRANSACTION_TYPE_BULK",
                params("search_criteria", "zzz", "new_type", "Rent")), 1L);

        assertEquals("No transactions found matching 'zzz'.", result);
        verify(transactionRepository, never()).save(any());
    }

    // ---------- SEARCH_TRANSACTIONS_BY_KEYWORD ----------

    @Test
    void searchReturnsFormattedListOfMatches() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "spotify"))
                .thenReturn(List.of(
                        txn("Spotify", "Entertainment", "-10.00", "2026-11-01", null),
                        txn("Spotify Premium", "Entertainment", "-12.00", "2026-11-02", null)));

        String result = service.executeComplexOperation(new AiActionClassification(
                "SEARCH_TRANSACTIONS_BY_KEYWORD", params("keyword", "spotify")), 1L);

        assertTrue(result.startsWith("Found 2 transactions matching 'spotify':"));
        assertTrue(result.contains("Spotify"));
    }

    @Test
    void searchFallsBackToDescriptionMatches() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "rent"))
                .thenReturn(List.of());
        when(transactionRepository.findByUser_UserIdAndTransactionDescriptionContaining(1L, "rent"))
                .thenReturn(List.of(txn("Flat", "Rent", "-800.00", "2026-11-01", null)));

        String result = service.executeComplexOperation(new AiActionClassification(
                "SEARCH_TRANSACTIONS_BY_KEYWORD", params("keyword", "rent")), 1L);

        assertTrue(result.contains("Flat"));
    }

    @Test
    void searchWithNoMatchesReportsNotFound() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "nothing"))
                .thenReturn(List.of());
        when(transactionRepository.findByUser_UserIdAndTransactionDescriptionContaining(1L, "nothing"))
                .thenReturn(List.of());

        String result = service.executeComplexOperation(new AiActionClassification(
                "SEARCH_TRANSACTIONS_BY_KEYWORD", params("keyword", "nothing")), 1L);

        assertEquals("No transactions found matching 'nothing'.", result);
    }

    // ---------- DELETE_TRANSACTION ----------

    @Test
    void deleteSingleMatchDeletesExactlyOnce() {
        Transaction t = txn("Coffee", "Groceries", "-4.50", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(t));

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION", params("keyword", "coffee")), 1L);

        assertTrue(result.startsWith("Deleted transaction: \"Coffee\""));
        verify(transactionRepository).delete(t);
        verify(transactionRepository, never()).deleteAll(anyList());
    }

    @Test
    void deleteMultipleMatchesBlockedUnlessMatchScopeAll() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(
                        txn("Coffee", "Groceries", "-4.50", "2026-11-01", null),
                        txn("Coffee Shop", "Groceries", "-5.50", "2026-11-02", null)));

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION", params("keyword", "coffee")), 1L);

        assertTrue(result.startsWith("Found 2 matching transactions."));
        assertTrue(result.contains("delete all"));
        verify(transactionRepository, never()).delete(any());
        verify(transactionRepository, never()).deleteAll(anyList());
    }

    @Test
    void deleteAllWithMatchScopeAllRemovesEveryMatch() {
        Transaction a = txn("Coffee", "Groceries", "-4.50", "2026-11-01", null);
        Transaction b = txn("Coffee Shop", "Groceries", "-5.50", "2026-11-02", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(a, b));

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION",
                params("keyword", "coffee", "match_scope", "all")), 1L);

        assertEquals("Deleted 2 transactions.", result);
        verify(transactionRepository).deleteAll(argThat(list ->
                list != null && ((List<?>) list).size() == 2));
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void deleteByTypeIdentifiesCandidates() {
        Transaction t = txn("Apartment", "Rent", "-900.00", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(1L, "Rent"))
                .thenReturn(List.of(t));

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION", params("type", "Rent")), 1L);

        assertTrue(result.startsWith("Deleted transaction: \"Apartment\""));
        verify(transactionRepository).delete(t);
    }

    @Test
    void deleteByMalformedDateReportsUnreadableDate() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION", params("date", "tomorrow-ish")), 1L);

        assertEquals("I couldn't understand that date format.", result);
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void deleteWithoutAnyIdentifierExplainsWhatIsRequired() {
        String result = service.executeComplexOperation(
                new AiActionClassification("DELETE_TRANSACTION", Map.of()), 1L);

        assertTrue(result.contains("name, category, or date"));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void deleteWithNoMatchingTransactionsReportsNotFound() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "zzz"))
                .thenReturn(List.of());

        String result = service.executeComplexOperation(new AiActionClassification(
                "DELETE_TRANSACTION", params("keyword", "zzz")), 1L);

        assertEquals("No transactions found matching your criteria.", result);
    }

    // ---------- UPDATE_SINGLE_TRANSACTION ----------

    @Test
    void updateSingleAppliesChangesAndReportsEachOne() {
        Transaction t = txn("Coffee", "Other", "-4.50", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("keyword", "coffee", "update_name", "Flat White",
                        "update_type", "Groceries", "amount", "-6.00",
                        "update_date", "2026-11-15")), 1L);

        assertTrue(result.startsWith("Updated \"Flat White\" (Groceries)"));
        assertTrue(result.contains("name changed to \"Flat White\""));
        assertTrue(result.contains("category changed to Groceries"));
        // state assertions on the real entity
        assertEquals("Flat White", t.getTransactionName());
        assertEquals("Groceries", t.getTransactionType());
        assertEquals(new BigDecimal("-6.00"), t.getTransactionAmount());
        assertEquals(LocalDate.of(2026, 11, 15), t.getTransactionDate());
    }

    @Test
    void updateSingleWithMultipleCandidatesAsksForSpecificity() {
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(
                        txn("Coffee", "Other", "-4.50", "2026-11-01", null),
                        txn("Coffee Shop", "Other", "-5.50", "2026-11-02", null)));

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("keyword", "coffee", "update_type", "Groceries")), 1L);

        assertTrue(result.contains("Found 2 matching transactions."));
        assertTrue(result.contains("be more specific"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateSinglePreservesUnspecifiedFields() {
        // updateDetails passes null for untouched fields; the real entity's
        // null-guard keeps the old values — assert they survive.
        Transaction t = txn("Coffee", "Other", "-4.50", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("keyword", "coffee", "update_type", "Entertainment")), 1L);

        assertEquals("Entertainment", t.getTransactionType());   // changed
        assertEquals("Coffee", t.getTransactionName());          // preserved
        assertEquals(new BigDecimal("-4.50"), t.getTransactionAmount()); // preserved
        assertEquals(LocalDate.of(2026, 11, 1), t.getTransactionDate()); // preserved
    }

    @Test
    void updateSingleWithUnparseableAmountFailsCleanly() {
        Transaction t = txn("Coffee", "Other", "-4.50", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionNameContaining(1L, "coffee"))
                .thenReturn(List.of(t));

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("keyword", "coffee", "amount", "not-a-number")), 1L);

        assertEquals("I couldn't understand that amount.", result);
        verify(transactionRepository, never()).save(any());
        assertEquals("Other", t.getTransactionType()); // nothing was modified
    }

    @Test
    void updateSingleByTypeTakesMostRecentCandidate() {
        Transaction t = txn("Thing", "Other", "-1.00", "2026-11-01", null);
        when(transactionRepository.findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(1L, "Other"))
                .thenReturn(List.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("type", "Other", "update_description", "misc expense")), 1L);

        assertEquals("misc expense", t.getTransactionDescription());
        assertTrue(result.startsWith("Updated \"Thing\" (Other)"));
        verify(transactionRepository).save(t);
    }

    @Test
    void updateSingleByDateFiltersTransactions() {
        Transaction t = txn("Thing", "Other", "-1.00", "2026-11-10", null);
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("date", "2026-11-10", "update_name", "Renamed")), 1L);

        assertEquals("Renamed", t.getTransactionName());
        verify(transactionRepository).save(t);
    }

    @Test
    void updateSingleWithBadDateReportsUnreadableDate() {
        when(transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(1L))
                .thenReturn(List.of());

        String result = service.executeComplexOperation(new AiActionClassification(
                "UPDATE_SINGLE_TRANSACTION",
                params("date", "not-a-date", "update_name", "Renamed")), 1L);

        assertEquals("I couldn't understand that date format.", result);
    }

    @Test
    void updateSingleWithoutIdentifierExplainsWhatIsRequired() {
        String result = service.executeComplexOperation(
                new AiActionClassification("UPDATE_SINGLE_TRANSACTION", Map.of()), 1L);

        assertEquals("I need to know which transaction to update.", result);
        verifyNoInteractions(transactionRepository);
    }
}