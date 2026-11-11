/*
    Provides database operations to find user transactions and save them.
    Uses Spring Data JPA's JpaRepository.
*/


package com.pocketledger.repository;

import com.pocketledger.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUser_UserIdOrderByTransactionDateDesc(Long userId);
    List<Transaction> findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(Long userId, String transactionType);
    Optional<Transaction> findByUser_UserIdAndTransactionId(Long userId, Long transactionId);

    @Query("SELECT t FROM Transaction t WHERE t.user.userId = :userId AND LOWER(t.transactionDescription) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Transaction> findByUser_UserIdAndTransactionDescriptionContaining(@Param("userId") Long userId, @Param("keyword") String keyword);

    @Query("SELECT t FROM Transaction t WHERE t.user.userId = :userId AND LOWER(t.transactionName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Transaction> findByUser_UserIdAndTransactionNameContaining(@Param("userId") Long userId, @Param("keyword") String keyword);



}


