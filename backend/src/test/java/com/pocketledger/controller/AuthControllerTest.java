package com.pocketledger.controller;

import com.pocketledger.model.User;
import com.pocketledger.repository.UserRepository;
import com.pocketledger.service.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Load AuthController and Spring's web handling without a real server or database.
// These tests check request validation, controller responses, and session creation.
@WebMvcTest(AuthController.class)
class AuthControllerTest {
    // Send simulated HTTP requests and inspect their responses and server sessions.
    @Autowired MockMvc mvc;
    // Use controlled substitutes for database access and password hashing.
    // These tests do not verify the real database or the hashing algorithm.
    @MockitoBean UserRepository users;
    @MockitoBean PasswordHasher passwords;
    // Spaces in the names and mixed-case email let us check input normalization.
    private static final String SIGNUP = """
            {"firstName":" Sam ","lastName":" Lee ",
             "email":"Sam@Example.com","password":"Strong1!"}
            """;

    // Successful registration should save cleaned account details and start a login session.
    @Test
    void registrationSavesNamesAndHashAndStartsSession() throws Exception {
        // Arrange: the email is available, and the hashing service returns a known value.
        when(users.findByUserEmailIgnoreCase("sam@example.com")).thenReturn(Optional.empty());
        when(passwords.hash("Strong1!")).thenReturn("hashed-password");
        User saved = mock(User.class);
        when(saved.getUserId()).thenReturn(7L);
        when(saved.getUserEmail()).thenReturn("sam@example.com");
        // Inspect the account passed to persistence: trimmed names, lowercase email,
        // the hash returned by the service, and a starting balance of zero.
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            assertEquals("Sam", user.getFirstName());
            assertEquals("Lee", user.getLastName());
            assertEquals("sam@example.com", user.getUserEmail());
            assertEquals("hashed-password", user.getPasswordHash());
            assertEquals(0, user.getBalance().signum());
            return saved;
        });
        // Expect HTTP 201 (Created), the saved user ID, no password field in the JSON,
        // and that same user ID in the server session.
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(result -> assertEquals(7L,
                        result.getRequest().getSession(false).getAttribute("userId")));
    }

    // Logging in with an unknown email returns HTTP 401 (Unauthorized).
    // Login must not create an account or call the password service for a missing user.
    @Test
    void unknownLoginDoesNotCreateAccount() throws Exception {
        when(users.findByUserEmailIgnoreCase("sam@example.com")).thenReturn(Optional.empty());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sam@example.com","password":"Strong1!"}
                                """))
                .andExpect(status().isUnauthorized());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }

    // Registering an existing email returns HTTP 409 (Conflict), without creating
    // a session, saving another account, or calling the password service.
    @Test
    void duplicateRegistrationDoesNotSaveOrLogIn() throws Exception {
        when(users.findByUserEmailIgnoreCase("sam@example.com")).thenReturn(Optional.of(mock(User.class)));
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isConflict())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }

    // Reject passwords that are too short or lack an uppercase letter, number, or symbol.
    // Each invalid request should return HTTP 400 (Bad Request).
    @Test
    void registrationRejectsWeakPasswordsBeforeDatabaseAccess() throws Exception {
        for (String password : new String[]{"Ab1!", "lowercase1!", "NoNumber!", "NoSymbol1"}) {
            mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                            .content(SIGNUP.replace("Strong1!", password)))
                    .andExpect(status().isBadRequest());
        }
        // Validation must reject every example before database access or password hashing.
        verifyNoInteractions(users, passwords);
    }
}
