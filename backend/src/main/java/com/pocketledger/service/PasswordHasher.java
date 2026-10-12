package com.pocketledger.service;

import org.springframework.stereotype.Component;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

// Spring shares this password helper with controllers. Hashing is one-way:
// login checks a password by hashing it again, rather than decrypting stored data.
@Component
public class PasswordHasher {
    // Repeated hashing makes guessing passwords more expensive.
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        // A random salt makes two accounts with the same password get different hashes.
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        // Save the algorithm, work count, salt, and hash together in the existing column.
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    public boolean matches(String password, String stored) {
        // Read the saved salt and reuse it to check the entered password.
        // Unknown formats (including plaintext passwords) are rejected.
        if (stored == null) return false;
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")
                    || !parts[1].equals(Integer.toString(ITERATIONS))) return false;
            // Compare hash bytes using the timing-resistant comparison provided by Java.
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                    derive(password, Base64.getDecoder().decode(parts[2])));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing unavailable", exception);
        } finally {
            // Clear the password copy kept by the hashing specification.
            spec.clearPassword();
        }
    }
}
