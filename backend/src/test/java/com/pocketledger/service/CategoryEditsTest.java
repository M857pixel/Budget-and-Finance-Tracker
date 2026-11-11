package com.pocketledger.service;

import com.pocketledger.model.*;
import com.pocketledger.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CategoryEditsTest {
    final TransactionRepository repository=mock(TransactionRepository.class);
    final CategoryEdits edits=new CategoryEdits(repository);
    Transaction row(long id,String name,String category) {
        Transaction row=spy(new Transaction(new User("private@test.local","hash"),name,category,new BigDecimal("-4.25"),LocalDate.of(2026,11,1),"private note"));
        when(row.getTransactionId()).thenReturn(id);return row;
    }
    @Test void previewOnlyReadsOwnersRecordsAndNeedsExplicitConfirmation() {
        var coffee=row(10,"Coffee",null);var rent=row(11,"Rent","Rent");
        when(repository.findByUser_UserIdOrderByTransactionDateDesc(7L)).thenReturn(List.of(coffee,rent));
        var draft=edits.preview(7L,"autocategorize my transactions");
        assertEquals(1,draft.preview().changes().size());assertEquals("Groceries",draft.preview().changes().getFirst().to());
        assertNull(coffee.getTransactionType()); assertEquals(7L,draft.owner());
        verify(repository,never()).saveAll(any());
    }
    @Test void explicitCommandUsesScopedIdLookupAndOnlyChangesCategory() {
        var coffee=row(10,"Coffee","Other");
        when(repository.findByUser_UserIdAndTransactionId(7L,10L)).thenReturn(Optional.of(coffee));
        var draft=edits.preview(7L,"categorize #10 as Groceries");
        assertEquals("Other",coffee.getTransactionType());
        when(repository.lockOwnedCategoryChanges(7L,List.of(10L))).thenReturn(List.of(coffee));
        assertEquals(1,edits.apply(7L,draft,draft.preview().confirmationToken()));
        assertEquals("Groceries",coffee.getTransactionType());
        assertEquals(new BigDecimal("-4.25"),coffee.getTransactionAmount());assertEquals("Coffee",coffee.getTransactionName());
        assertEquals("private note",coffee.getTransactionDescription());
        verify(repository).saveAll(List.of(coffee));
    }
    @Test void wrongOwnerTokenExpirationOrStaleCategoryCannotApply() {
        var coffee=row(10,"Coffee","Other");
        when(repository.findByUser_UserIdAndTransactionId(7L,10L)).thenReturn(Optional.of(coffee));
        var draft=edits.preview(7L,"categorize #10 as Groceries");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> edits.apply(8L,draft,draft.preview().confirmationToken()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> edits.apply(7L,draft,"forged"));
        var expired=new CategoryEdits.Draft(7L,0,draft.preview());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> edits.apply(7L,expired,draft.preview().confirmationToken()));
        when(repository.lockOwnedCategoryChanges(7L,List.of(10L))).thenReturn(List.of(coffee));
        coffee.updateDetails(null,"Rent",null,null,null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> edits.apply(7L,draft,draft.preview().confirmationToken()));
        verify(repository,never()).saveAll(any());
    }
    @Test void unknownForeignIdsAndInvalidCategoriesCannotModifyRecords() {
        when(repository.findByUser_UserIdAndTransactionId(7L,99L)).thenReturn(Optional.empty());
        assertTrue(edits.preview(7L,"categorize #99 as Rent").preview().changes().isEmpty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> edits.preview(7L,"categorize #99 as ADMIN"));
        verify(repository,never()).saveAll(any());
    }
    @Test void keywordMatchingIsLiteralNotSqlAndUncertainRulesPreserveExistingChoices() {
        var row=row(10,"Starbucks","Rent");
        when(repository.findByUser_UserIdOrderByTransactionDateDesc(7L)).thenReturn(List.of(row));
        assertTrue(edits.preview(7L,"autocategorize my transactions").preview().changes().isEmpty());
        assertEquals(1,edits.preview(7L,"categorize \"starbucks\" as Groceries").preview().changes().size());
        assertTrue(edits.preview(7L,"categorize \"%\" as Groceries").preview().changes().isEmpty());
        assertFalse(edits.matches("How can I change categories?"));
    }
}
