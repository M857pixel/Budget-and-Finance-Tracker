/*
    Connects HTTP requests from the frontend to the repositories that read and
    write users and transactions.

    Start the backend before running these examples. Replace each placeholder
    with a value. The secured transaction endpoints require a login session for
    the same userId in the URL.

    1. Create a user (POST /api/users):
    PowerShell:
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users" -ContentType "application/json" -Body '{"email":"<email>","passwordHash":"<password>","firstName":"<first name>","lastName":"<last name>","balance":0.00}'

    Mac/Linux:
    curl -X POST "http://localhost:8080/api/users" -H "Content-Type: application/json" -d '{"email":"<email>","passwordHash":"<password>","firstName":"<first name>","lastName":"<last name>","balance":0.00}'

    2. Log in to create a session (POST /api/login; authentication endpoint):
    PowerShell:
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/login" -ContentType "application/json" -Body '{"email":"<email>","password":"<password>"}' -WebSession $session

    Mac/Linux:
    curl -c cookies.txt -X POST "http://localhost:8080/api/login" -H "Content-Type: application/json" -d '{"email":"<email>","password":"<password>"}'

    Use the same PowerShell $session with the following commands. For Mac/Linux,
    add -b cookies.txt to each secured curl command.

    3. Create a transaction (POST /api/users/{userId}/transactions):
    PowerShell:
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users/<userId>/transactions" -ContentType "application/json" -Body '{"name":"<name>","type":"<type>","amount":25.50,"date":"2026-10-25","description":"<description>"}' -WebSession $session

    Mac/Linux:
    curl -X POST "http://localhost:8080/api/users/<userId>/transactions" -H "Content-Type: application/json" -d '{"name":"<name>","type":"<type>","amount":25.50,"date":"2026-10-25","description":"<description>"}' -b cookies.txt

    4. Get a user's transaction total for a month (GET /api/users/{userId}/transactions/total?month=YYYY-MM):
    PowerShell:
    Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/users/<userId>/transactions/total?month=<YYYY-MM>" -WebSession $session

    Mac/Linux:
    curl -X GET "http://localhost:8080/api/users/<userId>/transactions/total?month=2026-10" -b cookies.txt

    5. Get transactions by type (GET /api/users/{userId}/transactions/type/{type}):
    PowerShell:
    Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/users/<userId>/transactions/type/<type>" -WebSession $session

    Mac/Linux:
    curl -X GET "http://localhost:8080/api/users/<userId>/transactions/type/<type>" -b cookies.txt

    6. Update a transaction (PATCH /api/users/{userId}/transactions/{transactionId}):
    Send only fields to update: name, type, amount, date, description.
    PowerShell:
    Invoke-RestMethod -Method Patch -Uri "http://localhost:8080/api/users/<userId>/transactions/<transactionId>" -ContentType "application/json" -Body '{"amount":85.50,"description":"Updated description"}' -WebSession $session

    Mac/Linux:
    curl -X PATCH "http://localhost:8080/api/users/<userId>/transactions/<transactionId>" -H "Content-Type: application/json" -d '{"amount":85.50,"description":"Updated description"}' -b cookies.txt

    7. Delete a transaction (DELETE /api/users/{userId}/transactions?transactionId={transactionId}):
    PowerShell:
    Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/users/<userId>/transactions?transactionId=<transactionId>" -WebSession $session

    Mac/Linux:
    curl -X DELETE "http://localhost:8080/api/users/<userId>/transactions?transactionId=<transactionId>" -b cookies.txt

    8. Delete a user and their transactions (DELETE /api/users/{userId}):
    PowerShell:
    Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/users/<userId>"

    Mac/Linux:
    curl -X DELETE "http://localhost:8080/api/users/<userId>"
*/

package com.pocketledger.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
// Allow the local frontend to send its login session cookie across ports.
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class LedgerController {

    //Stores the repositories
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    //Constructor: Spring supplies the repository objects
    public LedgerController(UserRepository userRepository, TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    /*
    Stores created user in Supabase
    @Return: UserResponse object (Contains: userID, email, firstName, lastName, balance)
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        User saved = userRepository.save(new User(request.email(), request.passwordHash(),
                request.firstName(), request.lastName(), request.balance()));
        return new UserResponse(saved.getUserId(), saved.getUserEmail(), saved.getFirstName(),
                saved.getLastName(), saved.getBalance());
    }

    /*
    Delete user by ID and all transactions tied to the user by userID
    @Return: no object Status: 204, No Content
     */
    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteUserById(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<Transaction> transactions = transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId);
        transactionRepository.deleteAll(transactions);
        userRepository.delete(user);
    }

    /*
    Stores created transaction in Supabase
    @Return: TransactionResponse object (Contains: transactionID, userID, name, type, amount, data, description)
     */
    @PostMapping("/users/{userId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@PathVariable Long userId,
                                                 @RequestBody CreateTransactionRequest request, HttpServletRequest httpRequest) {
        // Verify the logged-in account before saving a transaction under the URL user ID.
        requireUser(userId, httpRequest);
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

    /*
    Gets user transactions by userId and month. The session must match this ID before the
    repository loads only that user's rows for the Transaction History tab and totals the
    transaction amounts.
    @Return: List<TransactionResponse>
    */
    @GetMapping("/users/{userId}/transactions/total")
    public BigDecimal getTransactionTotalByMonth(@PathVariable Long userId,
                                                 @RequestParam java.time.YearMonth month,
                                                 HttpServletRequest httpRequest) {
        requireUser(userId, httpRequest);
        return transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId)
                .stream()
                .filter(transaction -> java.time.YearMonth.from(transaction.getTransactionDate()).equals(month))
                .map(Transaction::getTransactionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /*
    Categorization Query:
    Get transactions matching both the user ID and transaction type
    @Returns: List<TransactionResponse> (of all the same type and user)
    */
    @GetMapping("/users/{userId}/transactions/type/{type}")
    public List<TransactionResponse> getTransactionsByType(@PathVariable Long userId,
                                                           @PathVariable String type, HttpServletRequest httpRequest) {
        requireUser(userId, httpRequest);
        return transactionRepository
                .findByUser_UserIdAndTransactionTypeOrderByTransactionDateDesc(userId, type)
                .stream().map(LedgerController::toResponse).toList();
    }

    /*
    Update only the supplied details of a transaction owned by this user
    @Returns: TransactionResponse (Contains: transactionID, userID, name, type, amount, data, description)
    */
    @PatchMapping("/users/{userId}/transactions/{transactionId}")
    public TransactionResponse updateTransaction(@PathVariable Long userId,
                                                 @PathVariable Long transactionId,
                                                 @RequestBody UpdateTransactionRequest request, HttpServletRequest httpRequest) {
        requireUser(userId, httpRequest);
        Transaction transaction = transactionRepository.findByUser_UserIdAndTransactionId(userId, transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        transaction.updateDetails(
                request.name(),
                request.type(),
                request.amount(),
                request.date(),
                request.description()
        );
        Transaction saved = transactionRepository.save(transaction);
        return toResponse(saved);
    }

    /*
    Delete the transaction identified by its ID and userId
    @Returns: no object Status: 204, No Content
    */
    @DeleteMapping("/users/{userId}/transactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransactionById(@PathVariable Long userId, @RequestParam Long transactionId, HttpServletRequest httpRequest) {
        requireUser(userId, httpRequest);
        Transaction transaction = transactionRepository.findByUser_UserIdAndTransactionId(userId, transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        transactionRepository.delete(transaction);
    }


    // Used by each transaction endpoint before database access. AuthController stores
    // userId in the server session at login; changing the URL must not grant access
    // to another account's transactions.
    private static void requireUser(Long userId, HttpServletRequest request) {
        // Read an existing session without creating one for an unauthenticated request.
        var session = request.getSession(false);
        Object authenticatedUserId = session == null ? null : session.getAttribute("userId");
        if (authenticatedUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in.");
        }
        if (!userId.equals(authenticatedUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account does not own these transactions.");
        }
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

    //SpingBoot uses these data containers to convert between JSON and Java objects
    public record CreateUserRequest(String email, String passwordHash, String firstName,
                                    String lastName, BigDecimal balance) {}
    public record GetUserRequest(String email, String passwordHash) {}
    public record UserResponse(Long userId, String email, String firstName,
                               String lastName, BigDecimal balance) {}
    public record CreateTransactionRequest(String name, String type, BigDecimal amount,
                                           LocalDate date, String description) {}
    public record UpdateTransactionRequest(String name, String type, BigDecimal amount,
                                           LocalDate date, String description) {}
    public record TransactionResponse(Long transactionId, Long userId, String name, String type,
                                      BigDecimal amount, LocalDate date, String description) {}
}
