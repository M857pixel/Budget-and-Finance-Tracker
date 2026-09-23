package com.pocketledger.repository;

import com.pocketledger.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUser_UserIdOrderByTransactionDateDesc(Long userId);
}
