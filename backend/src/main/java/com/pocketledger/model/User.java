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

    //JPA needs a no argument constructor to create users when reading rows
    protected User() {}

    //Constructor to create user before saving
    public User(String userEmail, String passwordHash) {
        this.userEmail = userEmail;
        this.passwordHash = passwordHash;
    }

    //Getters
    public Long getUserId() { return userId; }
    public String getUserEmail() { return userEmail; }
    public String getPasswordHash() { return passwordHash; }
}
