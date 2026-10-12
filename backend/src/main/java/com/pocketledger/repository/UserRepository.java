package com.pocketledger.repository;

import com.pocketledger.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // Spring generates the database query from this method name.
    // Optional is empty when no account matches; IgnoreCase disregards email capitalization.
    Optional<User> findByUserEmailIgnoreCase(String email);
}
