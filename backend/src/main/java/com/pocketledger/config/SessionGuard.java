package com.pocketledger.config;

import com.pocketledger.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class SessionGuard extends OncePerRequestFilter {
    private final UserRepository users;
    private final Set<String> origins;
    public SessionGuard(UserRepository users, Environment env) {
        this.users = users;
        origins = Set.of(env.getProperty("AUTH_ALLOWED_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080").split(","));
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/")) { chain.doFilter(req,res); return; }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        // Browser unsafe requests require a trusted Origin. Non-browser clients must supply it too.
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod()) && (req.getHeader("Origin") == null || !origins.contains(req.getHeader("Origin")))) {
            res.sendError(403, "Untrusted or missing Origin"); return;
        }
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("userId") instanceof Long id) {
            var user = users.findById(id).orElse(null);
            Object started = session.getAttribute("authenticatedAt");
            if (user == null || !user.isEmailVerified()
                || !Objects.equals(session.getAttribute("sessionVersion"), user.getSessionVersion())
                || !(started instanceof Long time) || System.currentTimeMillis() - time > 43200000) {
                session.invalidate(); res.sendError(401, "Please log in again"); return;
            }
        }
        chain.doFilter(req,res);
    }
}
