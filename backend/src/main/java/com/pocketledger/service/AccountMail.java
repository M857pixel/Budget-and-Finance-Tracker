package com.pocketledger.service;

import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

@Component
public class AccountMail {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AccountMail.class);
    private final Environment env;
    private long nextSend;
    public AccountMail(Environment env) {
        this.env = env;
        logger.info("Account email mode: {}", env.getProperty("AUTH_MAIL_MODE", "dev"));
    }
    public synchronized void send(String email, String purpose, String token) {
        String link = env.getProperty("AUTH_FRONTEND_URL", "http://localhost:5173")
            + "/#" + purpose + "=" + token;
        if (env.getProperty("AUTH_MAIL_MODE", "dev").equals("dev")) {
            // Private, local outbox; never include tokens in application logs or API responses.
            try {
                Path dir = Path.of(env.getProperty("AUTH_DEV_OUTBOX", System.getProperty("java.io.tmpdir") + "/pocketledger-mail"));
                Files.createDirectories(dir); Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                Path file = Files.createTempFile(dir, "mail-", ".txt");
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(file, email + "\n" + link);
                return;
            } catch (java.io.IOException e) { throw new IllegalStateException("Development outbox unavailable"); }
        }
        if (!env.getProperty("AUTH_MAIL_MODE", "dev").equals("emailjs")) throw new IllegalStateException("Unknown mail mode");
        if (System.currentTimeMillis() < nextSend) throw new IllegalStateException("Mail provider cooldown");
        nextSend = System.currentTimeMillis() + 1100;
        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000); factory.setReadTimeout(10000);
            RestClient.builder().requestFactory(factory).build().post().uri("https://api.emailjs.com/api/v1.0/email/send")
                .body(Map.of("service_id", env.getRequiredProperty("EMAILJS_SERVICE_ID"),
                    "template_id", env.getRequiredProperty(purpose.equals("verify") ? "EMAILJS_VERIFY_TEMPLATE_ID" : "EMAILJS_RESET_TEMPLATE_ID"),
                    "user_id", env.getRequiredProperty("EMAILJS_PUBLIC_KEY"),
                    "accessToken", env.getRequiredProperty("EMAILJS_PRIVATE_KEY"),
                    "template_params", Map.of("to_email", email, "action_url", link)))
                .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // Provider bodies can contain recipient details; log only the HTTP status.
            logger.warn("EmailJS delivery failed (HTTP {}). Check service/template settings, server API permissions, and quota in EmailJS.", e.getStatusCode().value());
            nextSend = System.currentTimeMillis() + 60000;
            throw new IllegalStateException("Email delivery unavailable");
        } catch (RuntimeException e) {
            logger.warn("Email delivery failed ({}). Check backend email configuration and connectivity.", e.getClass().getSimpleName());
            nextSend = System.currentTimeMillis() + 60000;
            throw new IllegalStateException("Email delivery unavailable");
        }
    }
}
