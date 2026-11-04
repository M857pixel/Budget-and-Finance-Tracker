/**
 * In-memory pending action state store with automatic expiration.
 *
 * Implements a per-user staging area for write operations awaiting confirmation.
 * Stores classified actions temporarily after initial classification, allowing
 * users to confirm or cancel before database modifications occur.

 */


package com.pocketledger.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class AiPendingActionStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(AiActionClassification classification, Instant createdAt) {}

    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    public void put(Long userId, AiActionClassification classification) {
        pending.put(userId, new Entry(classification, Instant.now()));
    }

    /** Inspects the pending action for a user without consuming it, respecting expiration. */
    public Optional<AiActionClassification> peek(Long userId) {
        Entry e = pending.get(userId);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.createdAt().plus(TTL))) {
            pending.remove(userId);
            return Optional.empty();
        }
        return Optional.of(e.classification());
    }

    /** Removes and returns the pending action for a user when confirmation is received. */
    public Optional<AiActionClassification> consume(Long userId) {
        Entry e = pending.remove(userId);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.createdAt().plus(TTL))) return Optional.empty();
        return Optional.of(e.classification());
    }

    /** Clears any pending action stored for a user. */
    public void clear(Long userId) {
        pending.remove(userId);
    }
}