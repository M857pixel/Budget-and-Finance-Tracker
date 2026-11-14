package com.pocketledger.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "account_tokens")
public class AccountToken {
    @Id @Column(length = 64) public String hash;
    @Column(nullable = false) public Long userId;
    @Column(nullable = false) public String purpose;
    @Column(nullable = false) public Instant expiresAt;
    protected AccountToken() {}
    public AccountToken(String hash, Long userId, String purpose, Instant expiresAt) {
        this.hash = hash; this.userId = userId; this.purpose = purpose; this.expiresAt = expiresAt;
    }
}
