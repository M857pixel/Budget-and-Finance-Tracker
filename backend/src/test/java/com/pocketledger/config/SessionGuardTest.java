package com.pocketledger.config;
import com.pocketledger.model.User;
import com.pocketledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SessionGuardTest {
    @Test void rejectsCrossSiteAndMissingOrigins() throws Exception {
        for (String origin : new String[]{"", "https://evil.example"}) {
            var request = new MockHttpServletRequest("POST","/api/logout");
            if (!origin.isEmpty()) request.addHeader("Origin",origin);
            var response = new MockHttpServletResponse();
            new SessionGuard(mock(UserRepository.class),new MockEnvironment()).doFilter(request,response,new MockFilterChain());
            assertEquals(403,response.getStatus());
        }
    }
    @Test void passwordResetInvalidatesOldSession() throws Exception {
        var users = mock(UserRepository.class);
        User user = new User("a@example.com","hash"); user.verifyEmail(); user.resetPassword("new-hash");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var request = new MockHttpServletRequest("GET","/api/users/1/transactions");
        request.getSession().setAttribute("userId",1L);
        request.getSession().setAttribute("sessionVersion",0L);
        request.getSession().setAttribute("authenticatedAt",System.currentTimeMillis());
        var response = new MockHttpServletResponse();
        new SessionGuard(users,new MockEnvironment()).doFilter(request,response,new MockFilterChain());
        assertEquals(401,response.getStatus()); assertNull(request.getSession(false));
    }
}
