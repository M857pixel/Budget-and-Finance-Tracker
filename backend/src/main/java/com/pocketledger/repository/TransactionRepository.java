package com.pocketledger.repository;

import com.pocketledger.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;


// provides database operations for Transaction objects
// JpaRepository includes basic CRUD functions already
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

}
