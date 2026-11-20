package com.pocketledger.service;

import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecurringTransactionJobTest {
    @Autowired TransactionRepository transactions;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void postsMissedMonthsOnceAndStopsWhenToggledOff() {
        var user = users.save(new User("recurring@isolated.test", "hash"));
        var source = new Transaction(user, "Rent", "Rent", new BigDecimal("-1200.00"),
                LocalDate.of(2026, 1, 31), "Monthly rent", true);
        source.advanceRecurringDate(LocalDate.of(2026, 2, 1));
        transactions.save(source);
        transactions.save(new Transaction(user, "Groceries", "Groceries", new BigDecimal("-55.00"),
                LocalDate.of(2026, 1, 31), null));
        var job = new RecurringTransactionJob(transactions, transactionManager);

        job.postDueTransactions(LocalDate.of(2026, 3, 30));
        assertThat(transactions.findAll()).hasSize(3);

        job.postDueTransactions(LocalDate.of(2026, 4, 30));
        job.postDueTransactions(LocalDate.of(2026, 4, 30));

        var entries = transactions.findByUser_UserIdOrderByTransactionDateDesc(user.getUserId());
        assertThat(entries).hasSize(5);
        assertThat(entries.subList(0, 3)).extracting(Transaction::getTransactionDate)
                .containsExactly(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 2, 28));
        assertThat(entries.subList(0, 3)).allSatisfy(occurrence -> {
            assertThat(occurrence.getRecurringTransaction()).isFalse();
            assertThat(occurrence.getTransactionName()).isEqualTo("Rent");
            assertThat(occurrence.getTransactionType()).isEqualTo("Rent");
            assertThat(occurrence.getTransactionDescription()).isEqualTo("Monthly rent");
            assertThat(occurrence.getTransactionAmount()).isEqualByComparingTo("-1200.00");
        });

        var storedSource = transactions.findById(source.getTransactionId()).orElseThrow();
        storedSource.updateDetails(null, null, null, null, null, false);
        transactions.save(storedSource);
        job.postDueTransactions(LocalDate.of(2026, 5, 31));
        assertThat(transactions.findAll()).hasSize(5);
    }
}
