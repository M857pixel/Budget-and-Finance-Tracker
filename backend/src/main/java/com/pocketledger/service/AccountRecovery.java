package com.pocketledger.service;
import com.pocketledger.model.*;
import com.pocketledger.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Service
public class AccountRecovery {
    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final AccountMail mail;
    private final PasswordHasher passwords;
    public AccountRecovery(UserRepository users, AccountTokenRepository tokens, AccountMail mail, PasswordHasher passwords) {
        this.users=users; this.tokens=tokens; this.mail=mail; this.passwords=passwords;
    }
    public static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public void issue(Long id, String purpose) {
        User user = users.lockById(id).orElseThrow();
        if (purpose.equals("verify") && user.isEmailVerified()) return;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.deleteByUserIdAndPurpose(id, purpose); tokens.flush();
        tokens.saveAndFlush(new AccountToken(hash(raw), id, purpose, Instant.now().plusSeconds(purpose.equals("verify") ? 86400 : 1800)));
        mail.send(user.getUserEmail(), purpose, raw);
    }
    @Transactional
    public void consume(String raw, String purpose, String password) {
        Long owner = tokens.owner(hash(raw)).orElseThrow(AccountRecovery::invalid);
        User user = users.lockById(owner).orElseThrow(AccountRecovery::invalid);
        // Reload after taking the account lock: concurrent consumption can succeed only once.
        AccountToken token = tokens.findById(hash(raw)).orElseThrow(AccountRecovery::invalid);
        if (!token.purpose.equals(purpose) || !token.expiresAt.isAfter(Instant.now())) throw invalid();
        if (purpose.equals("verify")) user.verifyEmail();
        else user.resetPassword(passwords.hash(password));
        tokens.deleteByUserIdAndPurpose(user.getUserId(), purpose);
        users.save(user);
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link is invalid, expired, or already used. Request a new link."); }
}
