package com.pocketledger.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Editable fields for create and full replacement (PUT); validation matches the SQL column limits. */
public record TransactionRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 50) String transactionType,
        @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull LocalDate transactionDate,
        @Size(max = 250) String description
) {
}
