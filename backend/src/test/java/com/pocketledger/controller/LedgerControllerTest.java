package com.pocketledger.controller;

import com.pocketledger.model.Transaction;
import com.pocketledger.model.User;
import com.pocketledger.repository.TransactionRepository;
import com.pocketledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Load the controller and Spring's web request handling, without starting a real
// HTTP server or connecting to Supabase. These are focused MVC controller tests.
@WebMvcTest(LedgerController.class)
class LedgerControllerTest {
    // MockMvc sends simulated HTTP requests through Spring and checks the responses.
    @Autowired MockMvc mvc;
    // Replace database repositories with Mockito mocks whose behavior each test controls.
    @MockitoBean UserRepository users;
    @MockitoBean TransactionRepository transactions;
    // Shared example of the JSON the frontend sends when adding an expense.
    private static final String BODY = """
            {"name":"Groceries","type":"Groceries","amount":-12.34,
             "date":"2026-10-19","description":"Weekly shopping"}
            """;

    // A logged-in user can submit a transaction and receive HTTP 201 (Created).
    // Check the returned user ID and submitted fields, including the negative amount and calendar date.
    @Test
    void savesFieldsAndCalendarDateForAuthenticatedUser() throws Exception {
        // Arrange: pretend user 7 exists; return the submitted transaction from save().
        // No real database insert happens, and this mock does not generate an ID.
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(transactions.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
        // Act and assert: supply the login session directly, send JSON, and check the response.
        mvc.perform(post("/api/users/7/transactions").sessionAttr("userId", 7L)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.name").value("Groceries"))
                .andExpect(jsonPath("$.type").value("Groceries"))
                .andExpect(jsonPath("$.amount").value(-12.34))
                .andExpect(jsonPath("$.date").value("2026-10-19"))
                .andExpect(jsonPath("$.description").value("Weekly shopping"));
    }

    // History requests must use the repository query scoped to the logged-in user.
    // This checks which query is called, rather than real database rows or sorting.
    @Test
    void historyQueriesOnlyTheAuthenticatedUsersRows() throws Exception {
        mvc.perform(get("/api/users/7/transactions").sessionAttr("userId", 7L))
                .andExpect(status().isOk());
        // Verify that the controller asks specifically for user 7's transactions.
        verify(transactions).findByUser_UserIdOrderByTransactionDateDesc(7L);
    }

    // Missing login returns HTTP 401; requesting another user's data returns HTTP 403.
    // Check an unauthenticated history request and mismatched-user history/create requests.
    @Test
    void rejectsMissingSessionsAndOtherUserIdsBeforeDatabaseAccess() throws Exception {
        mvc.perform(get("/api/users/7/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/7/transactions").sessionAttr("userId", 8L))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/7/transactions").sessionAttr("userId", 8L)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        // All three requests must be rejected before either repository is accessed.
        verifyNoInteractions(users, transactions);
    }

    // Before sending JSON across ports, browsers send an OPTIONS (CORS preflight) request.
    // Both local frontend addresses must be allowed to send credentialed requests.
    // This checks permission headers; it does not test actual browser cookie behavior.
    @Test
    void permitsSessionCookiesFromFrontend() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mvc.perform(options("/api/users/7/transactions").header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }
}
