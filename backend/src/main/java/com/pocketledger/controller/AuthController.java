package com.pocketledger.controller;

import com.pocketledger.model.User;
import com.pocketledger.repository.UserRepository;
import com.pocketledger.service.PasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;

// Receives login/logout requests from the React form. CORS allows the local
// frontend to send the session cookie even though it uses a different port.
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"}, allowCredentials = "true")
public class AuthController {
    private final UserRepository users;
    private final PasswordHasher passwords;

    public AuthController(UserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest credentials, HttpServletRequest request) {
        // Normalize only the email. Password capitalization and spaces stay unchanged.
        String email = credentials.email().strip().toLowerCase(Locale.ROOT);
        User user = users.findByUserEmailIgnoreCase(email).orElse(null);
        boolean created = user == null;
        if (created) {
            // A new email creates an account; store a hash instead of the typed password.
            try {
                user = users.saveAndFlush(new User(email, passwords.hash(credentials.password())));
            } catch (DataIntegrityViolationException exception) {
                // Report rejected database writes, including duplicate emails if the
                // database team adds a unique constraint.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account creation failed. Please retry login.");
            }
        } else if (!passwords.matches(credentials.password(), user.getPasswordHash())) {
            // An existing email with a wrong password must not create another account.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        // Replace any previous session, then remember the authenticated user on the
        // server. The browser receives a session-ID cookie, not the user's password.
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        request.getSession(true).setAttribute("userId", user.getUserId());
        return new LoginResponse(user.getUserId(), user.getUserEmail(), created);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        // End the server session without creating a session for an already logged-out user.
        if (request.getSession(false) != null) request.getSession(false).invalidate();
    }

    // Records describe incoming/outgoing JSON. @Valid applies these input rules
    // before login runs; the response deliberately contains no password or hash.
    public record LoginRequest(@NotBlank @Email @Size(max = 100) String email,
                               @NotBlank @Size(max = 256) String password) {}
    public record LoginResponse(Long userId, String email, boolean created) {}
}
