/*
    Connects HTTP requests from the frontend to the repositories that read and
    write to the database. Depends on both model and repository.

    Create User and Transaction in Terminal:
    1. Start backend
    2. In terminal cd to backend folder

    3. To Create User:

    NOTE: Omit curly braces around put here labels
    Windows Command:
                $user = Invoke-RestMethod -Method Post
                -Uri "http://localhost:8080/api/users"
                -ContentType "application/json"
                -Body '{"email":"{put email here}","passwordHash":"{put password here}","firstName":"{put first name here}","lastName":"{put last name here}","balance":{put balance here}}'

    One Line for Convenience:
    $user = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users" -ContentType "application/json" -Body '{"email":"{put email here}","passwordHash":"{put password here}","firstName":"{put first name here}","lastName":"{put last name here}","balance":{put balance here}}'

    Mac Command:
                            curl -X POST http://localhost:8080/api/users \
                            -H "Content-Type: application/json" \
                            -d '{"email":"{put email here}","passwordHash":"{put password here}","firstName":"{put first name here}","lastName":"{put last name here}","balance":{put balance here}}'

    4. To Authorize A User By Email And Password Hash:

    Windows Command:
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users/login" -ContentType "application/json" -Body '{"email":"{put email here}","passwordHash":"{put password hash here}"}'

    Mac Command:
    curl -X POST "http://localhost:8080/api/users/login" \\
        -H "Content-Type: application/json" \\
        -d '{"email":"{put email here}","passwordHash":"{put password hash here}"}'

    5. To Check Whether A User Exists By Email:

    Windows Command:
    Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/users?email={put email here}"

    Mac Command:
    curl -X GET "http://localhost:8080/api/users?email={put email here}"

    6. To Create Transaction:

    NOTE: Omit curly braces around put here labels
    Windows Command:
                Invoke-RestMethod -Method Post
                -Uri "http://localhost:8080/api/users/{put user id here}/transactions"
                -ContentType "application/json"
                -Body '{"name":"{put name here}","type":"{put type here}","amount":{put amount here},"date":"{put date here YY-MM-DD}","description":"{put description here}"}'

    One line for Convenvience:
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/users/{put user id here}/transactions" -ContentType "application/json" -Body '{"name":"{put name here}","type":"{put type here}","amount":{put amount here},"date":"{put date here YY-MM-DD}","description":"{put description here}"}'

    Mac Command:
                curl -X POST http://localhost:8080/api/users/{put user id here}/transactions \
                            -H "Content-Type: application/json" \
                            -d '{"name":"{put name here}","type":"{put type here}","amount":{put amount here},"date":"{put date here YY-MM-DD}","description":"{put description here}"}'

    Response: Converted from below JSON to a transaction record using toResponse
    {
        "transactionId": {transactionId},
        "userId": {userId},
        "name": "{name}",
        "type": "{type}",
        "amount": {amount},
        "date": "{date: YY-MM-DD}",
        "description": "{description}"
    }

    7. To Delete Transaction:

    NOTE: Omit curly braces around put here labels
    Windows Command:
                Invoke-RestMethod -Method Delete
                -Uri "http://localhost:8080/api/users/{put userId here}/transactions?transactionId={put transactionId here}"

    One Line for Convenience:
    Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/users/{put userId here}/transactions?transactionId={put transactionId here}"

    Mac Command:
                curl -X DELETE "http://localhost:8080/api/users/{put userId here}/transactions?transactionId={put transactionId here}"

    8. To Edit Transaction:

    NOTE: Omit curly braces around put here labels. Include only the fields you want to update in the body.
    Windows Command:
                Invoke-RestMethod -Method Patch
                -Uri "http://localhost:8080/api/users/{put userId here}/transactions/{put transactionId here}"
                -ContentType "application/json"
                -Body '{"amount":{put amount here},"description":"{put description here}"}'

    One Line for Convenience:
    Invoke-RestMethod -Method Patch -Uri "http://localhost:8080/api/users/{userId}/transactions/{transactionId}" -ContentType "application/json" -Body '{"amount":85.50,"description":"Updated description"}'

    Mac Command:
                curl -X PATCH "http://localhost:8080/api/users/{put userId here}/transactions/{put transactionId here}" \
                            -H "Content-Type: application/json" \
                            -d '{"amount":85.50,"description":"Updated description"}'

    9. To Delete User And Their Transactions:

    NOTE: Omit curly braces around put here labels.
    Windows Command:
                Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/users/{put userID here}"

    Mac Command:
                curl -X DELETE "http://localhost:8080/api/users/{put userID here}"

    10. To Get Transactions By Type:

    NOTE: Omit curly braces around put here labels.
    Windows Command:
                Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/users/{put userId here}/transactions/type/{put type here}"

    Mac Command:
                curl -X GET "http://localhost:8080/api/users/{put userId here}/transactions/type/{put type here}"

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
    Gets user transactions by userId. The session must match this ID before the
    repository loads only that user's rows for the Transaction History tab.
    @Return: List<TransactionResponse>
    */
    @GetMapping("/users/{userId}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable Long userId, HttpServletRequest httpRequest) {
        requireUser(userId, httpRequest);
        return transactionRepository.findByUser_UserIdOrderByTransactionDateDesc(userId)
                .stream().map(LedgerController::toResponse).toList();
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
