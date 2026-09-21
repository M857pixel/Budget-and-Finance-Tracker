package com.pocketledger.controller;
// Provide api endpoints for frontend use

import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@CrossOrigin(origins = "http://localhost:5173")
public class TransactionController {

    private final TransactionRepository repository;

    //give the controller access to the transaction repository
    public TransactionController(TransactionRepository repository) {
        this.repository = repository;
    }

    //CREATE - create a new transaction in the Database
    @PostMapping
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        return repository.save(transaction);
    }

    // READ - return ALL transactions from the database
    @GetMapping
    public List<Transaction> getTransactions() {
        return repository.findAll();
    }

    // READ 1 - return 1 transaction by using its id
    @GetMapping("/{id}")
    public Transaction getTransaction(@PathVariable Long id) {
        return repository.findById(id).orElse(null);
    }

    //UPDATE - find a transaction and update its info
    @PutMapping("/{id}")
    public Transaction updateTransaction(@PathVariable Long id,
                                         @RequestBody Transaction updatedTransaction){
        Transaction existingTransaction = repository.findById(id).orElse(null);

        if (existingTransaction == null) return null;

        existingTransaction.setAmount(updatedTransaction.getAmount());
        existingTransaction.setDescription(updatedTransaction.getDescription());
        existingTransaction.setCategory(updatedTransaction.getCategory());

        return repository.save(existingTransaction);
    }

    //DELETE using id
    @DeleteMapping("/{id}")
    public void deleteTransaction(@PathVariable Long id) {
        repository.deleteById(id);
    }
    
}
