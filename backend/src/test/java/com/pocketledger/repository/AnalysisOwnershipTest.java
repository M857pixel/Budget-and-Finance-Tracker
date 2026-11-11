package com.pocketledger.repository;

import com.pocketledger.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import java.time.LocalDate;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Real JPQL on isolated, disposable H2; never uses the configured shared datasource. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.datasource.url=jdbc:h2:mem:phase2;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.jpa.show-sql=false"})
class AnalysisOwnershipTest {
    @Autowired UserRepository users;
    @Autowired TransactionRepository transactions;
    @Test void aggregateFiltersOwnerPeriodSignsAndKeepsDecimalPrecision() {
        var alice=users.saveAndFlush(new User("alice@isolated.test","hash"));
        var bob=users.saveAndFlush(new User("bob@isolated.test","hash"));
        LocalDate day=LocalDate.of(2026,11,9);
        transactions.save(new Transaction(alice,"private merchant","Groceries",new BigDecimal("-0.10"),day,"private note"));
        transactions.save(new Transaction(alice,"private merchant","Groceries",new BigDecimal("-0.20"),day,"private note"));
        transactions.save(new Transaction(alice,"refund","Groceries",new BigDecimal("0.05"),day,"private note"));
        transactions.save(new Transaction(bob,"secret","Groceries",new BigDecimal("-9999.99"),day,"secret"));
        transactions.save(new Transaction(alice,"old","Groceries",new BigDecimal("-8888.88"),day.minusMonths(1),"secret"));
        transactions.flush();
        var rows=transactions.analysisTotals(alice.getUserId(),day.withDayOfMonth(1),day);
        assertEquals(1,rows.size());
        assertEquals(0,new BigDecimal("0.30").compareTo(rows.getFirst().getExpense()));
        assertEquals(0,new BigDecimal("0.05").compareTo(rows.getFirst().getIncome()));
        assertEquals(11,rows.getFirst().getMonth()); assertEquals(2026,rows.getFirst().getYear());
        assertTrue(transactions.analysisTotals(-1L,day.withDayOfMonth(1),day).isEmpty());
    }
    @Test void categoryLocksAreAlsoOwnerScopedEvenWithForeignIds() {
        var alice=users.saveAndFlush(new User("alice-lock@isolated.test","hash"));
        var bob=users.saveAndFlush(new User("bob-lock@isolated.test","hash"));
        var a=transactions.saveAndFlush(new Transaction(alice,"Coffee","Other",new BigDecimal("-4"),LocalDate.of(2026,11,1),null));
        var b=transactions.saveAndFlush(new Transaction(bob,"Coffee","Other",new BigDecimal("-8"),LocalDate.of(2026,11,1),null));
        var rows=transactions.lockOwnedCategoryChanges(alice.getUserId(),java.util.List.of(a.getTransactionId(),b.getTransactionId()));
        assertEquals(1,rows.size());assertEquals(a.getTransactionId(),rows.getFirst().getTransactionId());
    }
}
