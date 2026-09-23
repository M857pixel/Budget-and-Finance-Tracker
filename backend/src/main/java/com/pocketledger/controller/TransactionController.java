package com.pocketledger.controller;

import com.pocketledger.dto.TransactionRequest;
import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

/** CRUD starting point for the finance tracker's transactions. */
@RestController
@RequestMapping("/api/transactions")
@CrossOrigin(origins = "http://localhost:5173")
public class TransactionController {
    // TODO: When login is implemented, derive userId from the signed-in user and
    // scope every read/write to that user. These template routes have no access control yet.
    private final TransactionRepository repository;

    public TransactionController(TransactionRepository repository) {
        this.repository = repository;
    }

    // CREATE: POST JSON with userId, name, amount, transactionDate (YYYY-MM-DD),
    // and optional transactionType/description. Returns 201 and the new resource URL.
    @PostMapping
    public ResponseEntity<Transaction> createTransaction(@Valid @RequestBody TransactionRequest request) {
        Transaction transaction = new Transaction();
        applyRequest(transaction, request);
        Transaction saved = repository.save(transaction);
        return ResponseEntity.created(URI.create("/api/transactions/" + saved.getId())).body(saved);
    }

    // READ ALL: Returns a JSON array; an empty database produces an empty array.
    @GetMapping
    public List<Transaction> getTransactions() {
        return repository.findAll();
    }

    // READ ONE: Look up a transaction by its database-generated ID.
    @GetMapping("/{id}")
    public Transaction getTransaction(@PathVariable Long id) {
        return findTransaction(id);
    }

    // EDIT: PUT replaces all editable fields. Required fields must be supplied again.
    @PutMapping("/{id}")
    public Transaction editTransaction(@PathVariable Long id, @Valid @RequestBody TransactionRequest request) {
        Transaction transaction = findTransaction(id);
        applyRequest(transaction, request);
        return repository.save(transaction);
    }

    // DELETE: An existing transaction is removed; success returns 204 with no body.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable Long id) {
        repository.delete(findTransaction(id));
    }

    // Share the same 404 response across read, edit, and delete for an unknown ID.
    private Transaction findTransaction(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    // Copy only allowed fields so request data cannot overwrite the transaction ID.
    private void applyRequest(Transaction transaction, TransactionRequest request) {
        transaction.setUserId(request.userId());
        transaction.setName(request.name());
        transaction.setTransactionType(request.transactionType());
        transaction.setAmount(request.amount());
        transaction.setTransactionDate(request.transactionDate());
        transaction.setDescription(request.description());
    }
}
