/*
    Spring Boot applies this handler when a matching exception escapes a controller method.

    Exceptions:
    (503 Service Unavailable)
    Unavailable database connection
    Failure accessing database resource
*/
package com.pocketledger.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/* Converts database connectivity failures into a consistent API response. */
@RestControllerAdvice
public class DatabaseExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseExceptionHandler.class);
    private static final String MESSAGE = "Database service is temporarily unavailable. Please try again later.";

    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class
    })
    public ResponseEntity<DatabaseErrorResponse> handleDatabaseUnavailable(RuntimeException exception) {
        logger.error("Database connectivity failure while handling request", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new DatabaseErrorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), MESSAGE));
    }

    public record DatabaseErrorResponse(int status, String message) {}
}
