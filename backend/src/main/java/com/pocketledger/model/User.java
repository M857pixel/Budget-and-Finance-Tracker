/*
   Maps to the user table using JPA.
   Represents a single row in the user table.

   @Entity tells JPA this class represents a database.
   @Table connects to table created in migration by name.
   @Id marks the primary key.
   @Column annotation connects java fields to Supabase column names.
   @GeneratedValue tells JPA it is a generated value when row is created.
 */

package com.pocketledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_email", nullable = false, length = 100)
    private String userEmail;

    @Column(name = "user_password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "user_firstname", length = 20)
    private String firstName;

    @Column(name = "user_lastname", length = 20)
    private String lastName;

    @Column(name = "user_balance", precision = 12, scale = 2)
    private BigDecimal balance;

    //JPA needs a no argument constructor to create users when reading rows
    protected User() {}

    //Constructor to create user before saving
    public User(String userEmail, String passwordHash) {
        // Keep email storage consistent regardless of which controller creates the user.
        this.userEmail = userEmail.strip().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
    }

    public User(String userEmail, String passwordHash, String firstName, String lastName, BigDecimal balance) {
        this(userEmail, passwordHash);
        this.firstName = firstName;
        this.lastName = lastName;
        this.balance = balance;
    }

    //Getters
    public Long getUserId() { return userId; }
    public String getUserEmail() { return userEmail; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public BigDecimal getBalance() { return balance; }
}
