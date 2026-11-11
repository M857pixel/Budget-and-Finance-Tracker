package com.pocketledger.service;
import com.pocketledger.model.*;
import com.pocketledger.repository.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountRecoveryTest {
    final UserRepository users = mock(UserRepository.class);
    final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    final AccountMail mail = mock(AccountMail.class);
    final PasswordHasher passwords = mock(PasswordHasher.class);
    final AccountRecovery recovery = new AccountRecovery(users,tokens,mail,passwords);
    @Test void issueStoresHashOnlyAndSendsRawToken() {
        User user = new User("a@example.com", "hash");
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        recovery.issue(1L,"verify");
        var raw = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("a@example.com"),eq("verify"),raw.capture());
        assertEquals(43,raw.getValue().length());
        verify(tokens).saveAndFlush(argThat(t -> t.hash.equals(AccountRecovery.hash(raw.getValue())) && t.expiresAt.isAfter(Instant.now())));
    }
    @Test void verificationConsumesTokenWithoutChangingPassword() {
        User user = setup("verify", Instant.now().plusSeconds(60));
        recovery.consume("token","verify",null);
        assertTrue(user.isEmailVerified());
        verify(tokens).deleteByUserIdAndPurpose(1L,"verify");
        verifyNoInteractions(passwords);
    }
    @Test void resetChangesHashAndInvalidatesSessions() {
        User user = setup("reset", Instant.now().plusSeconds(60));
        when(passwords.hash("new-password")).thenReturn("new-hash");
        recovery.consume("token","reset","new-password");
        assertEquals("new-hash",user.getPasswordHash()); assertEquals(1,user.getSessionVersion());
        verify(tokens).deleteByUserIdAndPurpose(1L,"reset");
    }
    @Test void expiredOrWrongPurposeCannotBeUsed() {
        setup("verify",Instant.now().minusSeconds(1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> recovery.consume("token","verify",null));
        setup("reset",Instant.now().plusSeconds(60));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> recovery.consume("token","verify",null));
        verify(tokens,never()).deleteByUserIdAndPurpose(any(),any());
    }
    @Test void missingOrConsumedTokenIsRejected() {
        when(tokens.owner(anyString())).thenReturn(Optional.empty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> recovery.consume("used","reset","password"));
    }
    User setup(String purpose, Instant expiry) {
        User user = spy(new User("a@example.com","old-hash"));
        when(user.getUserId()).thenReturn(1L);
        when(tokens.owner(anyString())).thenReturn(Optional.of(1L));
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        when(tokens.findById(anyString())).thenReturn(Optional.of(new AccountToken("hash",1L,purpose,expiry)));
        return user;
    }
}
