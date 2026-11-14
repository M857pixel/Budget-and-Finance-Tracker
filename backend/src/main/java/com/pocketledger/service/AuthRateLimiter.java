package com.pocketledger.service;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.HashMap;
@Component
public class AuthRateLimiter {
    private final HashMap<String, long[]> counters = new HashMap<>();
    public synchronized void check(String key, int limit, long window) {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> e.getValue()[0] <= now);
        if (!counters.containsKey(key) && counters.size() >= 10000)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again later.");
        long[] c = counters.computeIfAbsent(key, k -> new long[]{now + window, 0});
        if (++c[1] > limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
    }
}
