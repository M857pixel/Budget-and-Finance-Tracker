package com.pocketledger.service;

import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.pocketledger.service.AiInputMinimizer.*;

class AiSecurityTest {
    final ChatSanitizer sanitizer=new ChatSanitizer();
    final HttpClient client=mock(HttpClient.class);
    final AiAssistantService ai=new AiAssistantService("backend-test-secret",client);
    ChatSanitizer.Message question() { return sanitizer.sanitize("Explain compound interest for alice@example.com."); }
    @SuppressWarnings("unchecked")
    void provider(int status,String content) throws Exception {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    String envelope(String content) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
    }
    @Test void actualRequestUsesOnlySanitizedUserTextAndAuthorizationHeader() throws Exception {
        provider(200,envelope("{\"reply\":\"Compound interest grows on both principal and previous interest.\",\"diagram\":null}"));
        assertTrue(ai.converse(question()).reply().startsWith("Compound interest"));
        var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(),any(HttpResponse.BodyHandler.class));
        var request=captor.getValue();
        assertEquals(AiAssistantService.ENDPOINT,request.uri());
        assertEquals("Bearer backend-test-secret",request.headers().firstValue("Authorization").orElseThrow());
        assertTrue(request.headers().firstValue("Cookie").isEmpty());
        var text=new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { text.append(StandardCharsets.UTF_8.decode(b)); }
            public void onError(Throwable t) { fail(t); }
            public void onComplete() {}
        });
        assertEquals(ai.buildChatPayload(question()),text.toString());
        assertFalse(text.toString().contains("alice@")); assertFalse(text.toString().contains("backend-test-secret"));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.toString()).has("tools"));
    }
    @Test void toolsArbitraryOperationsOversizedAndInvalidProviderResponsesFailClosed() throws Exception {
        for(String raw:List.of("{\"choices\":[{\"message\":{\"content\":\"{}\",\"tool_calls\":[]}}]}","x".repeat(32769),"{}",
            envelope("{\"reply\":\"Done\",\"diagram\":null,\"sql\":\"DELETE FROM users\"}"),
            envelope("{\"reply\":\"a\",\"reply\":\"b\",\"diagram\":null}"))) {
            provider(200,raw); assertThrows(IllegalStateException.class,() -> ai.converse(question()));
        }
    }
    @Test void providerRateLimitAndKeyFailuresHaveDistinctSafeCodes() throws Exception {
        provider(429,"private provider details");
        var error=assertThrows(AiAssistantService.Unavailable.class,() -> ai.converse(question()));
        assertEquals(AiAssistantService.Failure.PROVIDER_RATE_LIMITED,error.failure());assertFalse(error.getMessage().contains("private"));
        provider(401,"private provider details");
        assertEquals(AiAssistantService.Failure.PROVIDER_AUTHORIZATION,assertThrows(AiAssistantService.Unavailable.class,() -> ai.converse(question())).failure());
        assertEquals(AiAssistantService.Failure.MISSING_KEY,assertThrows(AiAssistantService.Unavailable.class,() -> new AiAssistantService("",client).converse(question())).failure());
    }
    @Test void adapterHasNoFinancialOrOperationDependenciesAndOldDispatcherIsGone() throws Exception {
        for(var field:AiAssistantService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("repository")); assertFalse(field.getType().getName().contains("HttpSession"));
            assertFalse(field.getType().getName().contains("CategoryEdits"));
        }
        for(String removed:List.of("AiOrchestratorService","AiPendingActionStore","AiActionClassification","AiActionParameters"))
            assertThrows(ClassNotFoundException.class,() -> Class.forName("com.pocketledger.service."+removed));
    }
    @Test void financialFollowupsUseOnlyFiniteContextAndExactCalendarRanges() {
        var minimizer=new AiInputMinimizer();
        var selection=minimizer.prepare("groceries this month",null).selection();
        var followup=minimizer.prepare("what about last month as a line chart",selection).selection();
        assertEquals(Category.Groceries,followup.category()); assertEquals(Period.LAST_MONTH,followup.period());assertEquals(Chart.line,followup.chart());
        assertArrayEquals(new java.time.LocalDate[]{java.time.LocalDate.of(2024,2,1),java.time.LocalDate.of(2024,2,29)},range(Period.LAST_MONTH,java.time.LocalDate.of(2024,3,2)));
        assertTrue(minimizer.prepare("income last 12 months",null).supported());assertTrue(minimizer.prepare("income last 6 months",null).supported());
    }
}
