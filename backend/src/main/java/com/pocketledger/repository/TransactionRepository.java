package com.pocketledger.repository;

import com.pocketledger.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data supplies save, findAll, findById, and delete without handwritten SQL.
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
