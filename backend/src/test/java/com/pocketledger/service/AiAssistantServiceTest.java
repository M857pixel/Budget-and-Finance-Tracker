package com.pocketledger.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiAssistantServiceTest {

    private AiAssistantService service;
    private HttpClient mockHttpClient;

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> okResponse(String content) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":"
                + jsonString(content) + "}}]}");
        return resp;
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** doReturn avoids the generic <T> inference problems of send(...). */
    private void stubSend(String content) throws Exception {
        doReturn(okResponse(content)).when(mockHttpClient)
                .send(any(HttpRequest.class), any());
    }

    private void stubSendRaw(HttpResponse<String> response) throws Exception {
        doReturn(response).when(mockHttpClient)
                .send(any(HttpRequest.class), any());
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new AiAssistantService("test-api-key");
        mockHttpClient = mock(HttpClient.class);
        inject(service, "httpClient", mockHttpClient);
    }

    @Test
    void classifyWithoutApiKeyThrowsIllegalState() {
        AiAssistantService noKey = new AiAssistantService("");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> noKey.classify("hello"));
        assertTrue(e.getMessage().contains("mistral.api-key is not set"));
    }

    @Test
    void classifyReturnsCleanedActionJson() throws Exception {
        stubSend("[{\"action\":\"NONE\"}]");
        assertEquals("[{\"action\":\"NONE\"}]", service.classify("what's the weather?"));
    }

    @Test
    void classifyWithPendingContextUsesSameSignature() throws Exception {
        stubSend("[{\"action\":\"CONFIRM_PENDING\"}]");
        assertEquals("[{\"action\":\"CONFIRM_PENDING\"}]",
                service.classify("yes", "{\"action\":\"CREATE_TRANSACTION\",...}"));
    }

    @Test
    void classifyStripsMarkdownCodeFencesFromAiOutput() throws Exception {
        stubSend("```json\n[{\"action\":\"NONE\"}]\n```");
        assertEquals("[{\"action\":\"NONE\"}]", service.classify("hello"));
    }

    @Test
    void classifyFallsBackToNoneWhenAiOutputIsNotJson() throws Exception {
        stubSend("Sure, how can I help?");
        assertEquals("{\"action\":\"NONE\"}", service.classify("hello"));
    }

    @Test
    void classifyFallsBackToNoneWhenResponseBodyHasNoChoices() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{}");
        stubSendRaw(resp);

        assertEquals("{\"action\":\"NONE\"}", service.classify("hello"));
    }

    @Test
    void classifySendsAuthorizationHeaderAndTargetsMistralEndpoint() throws Exception {
        stubSend("[{\"action\":\"NONE\"}]");

        service.classify("hello");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        HttpRequest sent = captor.getValue();
        assertEquals("https://api.mistral.ai/v1/chat/completions", sent.uri().toString());
        assertEquals("Bearer test-api-key",
                sent.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/json",
                sent.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void classifyThrowsWhenApiReturnsErrorStatus() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        stubSendRaw(resp);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.classify("hello"));
        assertTrue(e.getMessage().contains("HTTP 500"));
    }

    @Test
    void classifyWrapsIoErrorsAsIllegalState() throws Exception {
        doThrow(new IOException("connection refused")).when(mockHttpClient)
                .send(any(HttpRequest.class), any());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.classify("hello"));
        assertTrue(e.getMessage().contains("Could not reach Mistral"));
    }

    @Test
    void classifyHandlesInterruptionByRestoringFlagAndWrapping() throws Exception {
        doThrow(new InterruptedException("cancelled")).when(mockHttpClient)
                .send(any(HttpRequest.class), any());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.classify("hello"));
        assertTrue(e.getMessage().contains("Interrupted"));
        assertTrue(Thread.interrupted()); // consume the restored flag so it doesn't leak
    }
}