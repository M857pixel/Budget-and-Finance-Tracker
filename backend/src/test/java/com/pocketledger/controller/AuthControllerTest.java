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

@WebMvcTest(AuthController.class)
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean PasswordHasher passwords;
    private static final String SIGNUP = """
            {"firstName":" Sam ","lastName":" Lee ",
             "email":"Sam@Example.com","password":"Strong1!"}
            """;

    @Test
    void registrationSavesNamesAndHashAndStartsSession() throws Exception {
        when(users.findByUserEmailIgnoreCase("sam@example.com")).thenReturn(Optional.empty());
        when(passwords.hash("Strong1!")).thenReturn("hashed-password");
        User saved = mock(User.class);
        when(saved.getUserId()).thenReturn(7L);
        when(saved.getUserEmail()).thenReturn("sam@example.com");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            assertEquals("Sam", user.getFirstName());
            assertEquals("Lee", user.getLastName());
            assertEquals("sam@example.com", user.getUserEmail());
            assertEquals("hashed-password", user.getPasswordHash());
            assertEquals(0, user.getBalance().signum());
            return saved;
        });
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(result -> assertEquals(7L,
                        result.getRequest().getSession(false).getAttribute("userId")));
    }

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

    @Test
    void duplicateRegistrationDoesNotSaveOrLogIn() throws Exception {
        when(users.findByUserEmailIgnoreCase("sam@example.com")).thenReturn(Optional.of(mock(User.class)));
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isConflict())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void registrationRejectsWeakPasswordsBeforeDatabaseAccess() throws Exception {
        for (String password : new String[]{"Ab1!", "lowercase1!", "NoNumber!", "NoSymbol1"}) {
            mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                            .content(SIGNUP.replace("Strong1!", password)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(users, passwords);
    }
}
