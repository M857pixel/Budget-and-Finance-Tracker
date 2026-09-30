package com.pocketledger.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** Demonstrates the frontend connection without storing any data. */
@RestController
@RequestMapping("/api")
// Allow the local frontend to call the backend from a different port.
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class FrontendController {
    // The "Test backend" button calls GET /api/hello and displays this message.
    @GetMapping("/hello")
    public String hello() {
        return "Connected to PocketLedger backend.";
    }

    // The "Add" button sends JSON like {"amount": 10.50} to POST /api/test-record.
    @PostMapping("/test-record")
    @ResponseStatus(HttpStatus.OK)
    public AmountResponse addAmount(@Valid @RequestBody AmountRequest request) {
        // Send the received number back. Nothing is saved or added to a total.
        return new AmountResponse(request.amount());
    }

    // The amount is required; missing or invalid numbers return HTTP 400.
    public record AmountRequest(@NotNull BigDecimal amount) {}
    public record AmountResponse(BigDecimal amount) {}
}
