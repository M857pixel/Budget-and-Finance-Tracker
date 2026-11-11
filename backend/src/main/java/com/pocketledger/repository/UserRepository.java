package com.pocketledger.repository;

import com.pocketledger.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.userId = :id")
    Optional<User> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    // Spring generates the database query from this method name.
    // Optional is empty when no account matches; IgnoreCase disregards email capitalization.
    Optional<User> findByUserEmailIgnoreCase(String email);
}
