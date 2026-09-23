/*
   Maps to the transactions table using JPA.
   Represents a single row in the transactions table.

   @Entity tells JPA this class represents a database.
   @Table connects to table created in migration by name.
   @Id marks the primary key.
   @ManyToOne  tells many transactions can belong to one user.
   @JoinColumn maps that relationship to the foreign key column in migration.
   @Column annotation connects java fields to Supabase column names.
   @GeneratedValue tells JPA it is a generated value when row is created.
 */

package com.pocketledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "transaction_name", nullable = false, length = 50)
    private String transactionName;

    @Column(name = "transaction_type", length = 50)
    private String transactionType;

    @Column(name = "transaction_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal transactionAmount;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "transaction_description", length = 250)
    private String transactionDescription;

    //JPA needs a no argument constructor to create transactions when reading rows
    protected Transaction() {}

    //Constructor to create transaction before saving
    public Transaction(User user, String transactionName, String transactionType,
                       BigDecimal transactionAmount, LocalDate transactionDate,
                       String transactionDescription) {
        this.user = user;
        this.transactionName = transactionName;
        this.transactionType = transactionType;
        this.transactionAmount = transactionAmount;
        this.transactionDate = transactionDate;
        this.transactionDescription = transactionDescription;
    }

    //Getters
    public Long getTransactionId() { return transactionId; }
    public User getUser() { return user; }
    public String getTransactionName() { return transactionName; }
    public String getTransactionType() { return transactionType; }
    public BigDecimal getTransactionAmount() { return transactionAmount; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public String getTransactionDescription() { return transactionDescription; }
}
