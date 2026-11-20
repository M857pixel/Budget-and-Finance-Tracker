package com.pocketledger.service;

import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Component
public class RecurringTransactionJob {
    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionJob.class);

    private final TransactionRepository transactions;
    private final TransactionTemplate transactionTemplate;

    public RecurringTransactionJob(TransactionRepository transactions, PlatformTransactionManager transactionManager) {
        this.transactions = transactions;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 60_000)
    public void postDueTransactions() {
        postDueTransactions(LocalDate.now(ZoneOffset.UTC));
    }

    void postDueTransactions(LocalDate today) {
        for (Long sourceId : transactions.findDueRecurringIds(today)) {
            try {
                transactionTemplate.executeWithoutResult(status -> transactions.lockRecurringSource(sourceId)
                        .ifPresent(source -> postOccurrences(source, today)));
            } catch (RuntimeException exception) {
                log.error("Could not post recurring transaction {}", sourceId, exception);
            }
        }
    }

    private void postOccurrences(Transaction source, LocalDate today) {
        LocalDate nextDate = source.getRecurringNextDate();
        if (!source.getRecurringTransaction() || nextDate == null || nextDate.isAfter(today)) return;

        LocalDate anchor = source.getTransactionDate();
        YearMonth month = YearMonth.from(nextDate);
        YearMonth firstRepeatMonth = YearMonth.from(anchor).plusMonths(1);
        if (month.isBefore(firstRepeatMonth)) month = firstRepeatMonth;

        LocalDate occurrence = dateInMonth(month, anchor.getDayOfMonth());
        if (occurrence.isBefore(nextDate)) occurrence = dateInMonth(month.plusMonths(1), anchor.getDayOfMonth());

        while (!occurrence.isAfter(today)) {
            if (!transactions.existsByRecurringSourceIdAndRecurringOccurrenceDate(source.getTransactionId(), occurrence)) {
                transactions.save(source.createRecurringOccurrence(occurrence));
            }
            occurrence = dateInMonth(YearMonth.from(occurrence).plusMonths(1), anchor.getDayOfMonth());
        }
        source.advanceRecurringDate(occurrence);
    }

    private static LocalDate dateInMonth(YearMonth month, int anchorDay) {
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }
}
