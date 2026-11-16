package com.pocketledger.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AiPendingActionStoreTest {

    private AiPendingActionStore store;

    private AiActionClassification action(String name) {
        return new AiActionClassification(name, Map.of());
    }

    /**
     * Rebuilds the user's Entry with its createdAt rewound, so TTL expiry
     * can be tested without sleeping. Record fields are final, so the entry
     * is replaced via its canonical constructor rather than mutated.
     */
    private void ageEntry(Long userId, AiActionClassification classification, Duration age)
            throws Exception {
        Class<?> entryClass =
                Class.forName("com.pocketledger.service.AiPendingActionStore$Entry");
        Constructor<?> ctor = entryClass.getDeclaredConstructor(
                AiActionClassification.class, Instant.class);
        ctor.setAccessible(true);

        Field pendingField = AiPendingActionStore.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, Object> pending = (Map<Long, Object>) pendingField.get(store);

        Object entry = pending.get(userId);
        assertNotNull(entry, "expected a pending entry for user " + userId);

        Field createdAtField = entryClass.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        Instant original = (Instant) createdAtField.get(entry);

        pending.put(userId, ctor.newInstance(classification, original.minus(age)));
    }

    @BeforeEach
    void setUp() {
        store = new AiPendingActionStore();
    }

    @Test
    void peekReturnsStoredActionWithoutConsumingIt() {
        store.put(1L, action("CREATE_TRANSACTION"));

        Optional<AiActionClassification> first = store.peek(1L);
        Optional<AiActionClassification> second = store.peek(1L);

        assertTrue(first.isPresent());
        assertEquals("CREATE_TRANSACTION", first.get().action());
        assertTrue(second.isPresent()); // peek is non-destructive
    }

    @Test
    void peekReturnsEmptyWhenNothingStored() {
        assertTrue(store.peek(1L).isEmpty());
    }

    @Test
    void consumeRemovesAndReturnsTheAction() {
        store.put(1L, action("DELETE_TRANSACTION"));

        Optional<AiActionClassification> consumed = store.consume(1L);

        assertTrue(consumed.isPresent());
        assertEquals("DELETE_TRANSACTION", consumed.get().action());
        assertTrue(store.consume(1L).isEmpty()); // second consume finds nothing
    }

    @Test
    void consumeReturnsEmptyWhenNothingStored() {
        assertTrue(store.consume(1L).isEmpty());
    }

    @Test
    void expiredActionIsRemovedOnPeek() throws Exception {
        store.put(1L, action("CREATE_TRANSACTION"));
        ageEntry(1L, action("CREATE_TRANSACTION"), Duration.ofMinutes(6)); // TTL is 5 min

        assertTrue(store.peek(1L).isEmpty());

        // stale entry was actually evicted, not just hidden
        Field f = AiPendingActionStore.class.getDeclaredField("pending");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, ?> pending = (Map<Long, ?>) f.get(store);
        assertFalse(pending.containsKey(1L));
    }

    @Test
    void expiredActionYieldsEmptyOnConsume() throws Exception {
        store.put(1L, action("CREATE_TRANSACTION"));
        ageEntry(1L, action("CREATE_TRANSACTION"), Duration.ofMinutes(6));

        assertTrue(store.consume(1L).isEmpty());
    }

    @Test
    void actionWithinTtlIsStillReturned() throws Exception {
        store.put(1L, action("CREATE_TRANSACTION"));
        ageEntry(1L, action("CREATE_TRANSACTION"), Duration.ofMinutes(4));

        assertTrue(store.peek(1L).isPresent());
    }

    @Test
    void clearRemovesPendingAction() {
        store.put(1L, action("CREATE_TRANSACTION"));
        store.clear(1L);

        assertTrue(store.peek(1L).isEmpty());
        assertTrue(store.consume(1L).isEmpty());
    }

    @Test
    void clearIsSafeWhenNothingIsPending() {
        assertDoesNotThrow(() -> store.clear(42L));
    }

    @Test
    void usersAreIsolatedFromEachOther() {
        store.put(1L, action("CREATE_TRANSACTION"));

        assertTrue(store.peek(2L).isEmpty());
        assertTrue(store.peek(1L).isPresent());

        store.put(2L, action("DELETE_TRANSACTION"));
        assertEquals("DELETE_TRANSACTION", store.peek(2L).orElseThrow().action());
        assertEquals("CREATE_TRANSACTION", store.peek(1L).orElseThrow().action());

        store.consume(1L);
        assertTrue(store.peek(1L).isEmpty());
        assertTrue(store.peek(2L).isPresent());
    }

    @Test
    void puttingAgainReplacesThePreviousAction() {
        store.put(1L, action("CREATE_TRANSACTION"));
        store.put(1L, action("UPDATE_SINGLE_TRANSACTION"));

        assertEquals("UPDATE_SINGLE_TRANSACTION", store.peek(1L).orElseThrow().action());
    }
}