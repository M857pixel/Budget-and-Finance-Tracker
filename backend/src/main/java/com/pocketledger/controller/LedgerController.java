/*
    Connects HTTP requests from the frontend to the repositories that read and
    write to the database. Depends on both model and repository.
 */

package com.pocketledger.controller;

import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
//Database classes
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class LedgerController {

    //Stores the repositories
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    //Constructor: Spring supplies the repository objects
    public LedgerController(UserRepository userRepository, TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    //Save created user in Supabase
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        User saved = userRepository.save(new User(request.email(), request.passwordHash()));
        return new UserResponse(saved.getUserId(), saved.getUserEmail()); //Return user ID and email
    }

    /*
        Add a transaction to the transactions table.
        1. Ensures user exists
        2. Creates transaction associated with that user.
        3. Returns the saved record.
     */
    @PostMapping("/users/{userId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@PathVariable Long userId,
                                                 @RequestBody CreateTransactionRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Transaction saved = transactionRepository.save(new Transaction(
                user,
                request.name(),
                request.type(),
                request.amount(),
                request.date(),
                request.description()
        ));
        return toResponse(saved);
    }

    //Calls repository query and converts each result into an API response
    @GetMapping("/users/{userId}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable Long userId) {
        return transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId)
                .stream().map(LedgerController::toResponse).toList();
    }

    //toResponse: Copies the response from Supabase into a transaction record, avoids using JSON.
    private static TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getTransactionId(),
                transaction.getUser().getUserId(),
                transaction.getTransactionName(),
                transaction.getTransactionType(),
                transaction.getTransactionAmount(),
                transaction.getTransactionDate(),
                transaction.getTransactionDescription()
        );
    }

    public record CreateUserRequest(String email, String passwordHash) {}
    public record UserResponse(Long userId, String email) {}
    public record CreateTransactionRequest(String name, String type, BigDecimal amount,
                                           LocalDate date, String description) {}
    public record TransactionResponse(Long transactionId, Long userId, String name, String type,
                                      BigDecimal amount, LocalDate date, String description) {}
}
