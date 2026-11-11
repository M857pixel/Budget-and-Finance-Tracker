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



    /** Read-only monthly/category aggregates, always scoped to the authenticated owner. */
    interface AnalysisTotal {
        Integer getYear();
        Integer getMonth();
        String getCategory();
        java.math.BigDecimal getExpense();
        java.math.BigDecimal getIncome();
    }

    @Query("""
        SELECT year(t.transactionDate) AS year, month(t.transactionDate) AS month,
               t.transactionType AS category,
               SUM(CASE WHEN t.transactionAmount < 0 THEN -t.transactionAmount ELSE 0 END) AS expense,
               SUM(CASE WHEN t.transactionAmount > 0 THEN t.transactionAmount ELSE 0 END) AS income
        FROM Transaction t
        WHERE t.user.userId = :owner AND t.transactionDate BETWEEN :start AND :end
        GROUP BY year(t.transactionDate), month(t.transactionDate), t.transactionType
        """)
    List<AnalysisTotal> analysisTotals(@Param("owner") Long owner,
        @Param("start") java.time.LocalDate start, @Param("end") java.time.LocalDate end);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.user.userId = :owner AND t.transactionId IN :ids ORDER BY t.transactionId")
    List<Transaction> lockOwnedCategoryChanges(@Param("owner") Long owner, @Param("ids") List<Long> ids);

}


