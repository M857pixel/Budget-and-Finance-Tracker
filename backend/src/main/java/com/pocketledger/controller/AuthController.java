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
        if (user == null || !passwords.matches(credentials.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        startSession(request, user);
        return new LoginResponse(user.getUserId(), user.getUserEmail(), false);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(
            @Valid @RequestBody RegisterRequest credentials,
            HttpServletRequest request) {

        String email = credentials.email().strip().toLowerCase(Locale.ROOT);

        if (users.findByUserEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "An account with this email already exists.");
        }

        User user;
        try {
            user = users.saveAndFlush(new User(
                    email,
                    passwords.hash(credentials.password()),
                    credentials.firstName().strip(),
                    credentials.lastName().strip(),
                    java.math.BigDecimal.ZERO
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Account creation failed.");
        }

        startSession(request, user);
        return new LoginResponse(user.getUserId(), user.getUserEmail(), true);
    }

    // start a fresh session after login or registration
    private void startSession(HttpServletRequest request, User user) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        request.getSession(true).setAttribute("userId", user.getUserId());

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
    public record RegisterRequest(
            @NotBlank @Size(max = 20) String firstName,
            @NotBlank @Size(max = 20) String lastName,
            @NotBlank @Email @Size(max = 50) String email,
            @NotBlank @Size(min = 8, max = 50)
            @jakarta.validation.constraints.Pattern(
                    regexp = "(?s)(?=.*[A-Z])(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).*",
                    message = "Password needs an uppercase letter, a number, and a symbol."
            )
            String password
    ) {}
    public record LoginResponse(Long userId, String email, boolean created) {}
}
