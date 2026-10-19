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

@WebMvcTest(LedgerController.class)
class LedgerControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean TransactionRepository transactions;
    private static final String BODY = """
            {"name":"Groceries","type":"Groceries","amount":-12.34,
             "date":"2026-10-19","description":"Weekly shopping"}
            """;

    @Test
    void savesFieldsAndCalendarDateForAuthenticatedUser() throws Exception {
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(transactions.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
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

    @Test
    void historyQueriesOnlyTheAuthenticatedUsersRows() throws Exception {
        mvc.perform(get("/api/users/7/transactions").sessionAttr("userId", 7L))
                .andExpect(status().isOk());
        verify(transactions).findByUser_UserIdOrderByTransactionDateDesc(7L);
    }

    @Test
    void rejectsMissingSessionsAndOtherUserIdsBeforeDatabaseAccess() throws Exception {
        mvc.perform(get("/api/users/7/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/7/transactions").sessionAttr("userId", 8L))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/7/transactions").sessionAttr("userId", 8L)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, transactions);
    }

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
