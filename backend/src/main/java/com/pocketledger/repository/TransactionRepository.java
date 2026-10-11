/*
    Provides database operations to find user transactions and save them.
    Uses Spring Data JPA's JpaRepository.
*/


package com.pocketledger.repository;

import com.pocketledger.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUser_UserIdOrderByTransactionDateDesc(Long userId);
    List<Transaction> findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(Long userId, String transactionType);
    Optional<Transaction> findByUser_UserIdAndTransactionId(Long userId, Long transactionId);
}
