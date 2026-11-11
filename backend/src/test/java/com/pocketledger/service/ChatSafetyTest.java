package com.pocketledger.service;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSafetyTest {
    final ChatSanitizer sanitizer=new ChatSanitizer();
    final AiAssistantService ai=new AiAssistantService("test-key",mock(HttpClient.class));
    @Test void blocksCredentialValuesRatherThanSendingRedactedSecretPrompts() {
        for(String input:List.of("my password is hunter2","api_key=sample-secret","Bearer some-token","jdbc:postgresql://private","123-45-6789","4111 1111 1111 1111")) {
            var message=sanitizer.sanitize(input);assertTrue(message.blocked());assertEquals("",message.text());
            assertThrows(IllegalArgumentException.class,() -> ai.converse(message));
        }
    }
    @Test void generalKnowledgeAndRedactedContactTextAreSupportedWithoutBackendData() throws Exception {
        var message=sanitizer.sanitize("Explain compound interest, email alice@example.com; I have $123.45.");
        assertFalse(message.blocked());
        String payload=ai.buildChatPayload(message);
        assertTrue(payload.contains("Explain compound interest"));assertFalse(payload.contains("alice@"));assertFalse(payload.contains("123.45"));assertFalse(payload.contains("test-key"));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).has("tools"));
        assertFalse(sanitizer.sanitize("How do I reset a password?").blocked());
    }
    @Test void identifiersPhonesAndPersonalAmountsAreMinimized() {
        var text=sanitizer.sanitize("Explain savings. My account id is 98765; user id: 456; call 555-123-4567. I earn 50000 yearly.").text();
        assertFalse(text.contains("98765"));assertFalse(text.contains("456"));assertFalse(text.contains("555-123-4567"));assertFalse(text.contains("50000"));
    }
    @Test void diagramSchemaIsDataOnlyBoundedAndCannotSupplyOperationsOrMarkup() throws Exception {
        var reply=ai.validateChat("{\"reply\":\"Here is the cycle.\",\"diagram\":{\"nodes\":[\"Water\",\"Vapor\"],\"edges\":[{\"from\":0,\"to\":1}]}}");
        assertEquals(2,reply.diagram().nodes().size());
        for(String raw:List.of("{\"reply\":\"Done\",\"diagram\":null,\"sql\":\"DELETE FROM users\"}",
            "{\"reply\":\"Chart\",\"diagram\":{\"nodes\":[\"A\"],\"edges\":[{\"from\":0,\"to\":9}]}}",
            "{\"reply\":\"SVG\",\"diagram\":\"<svg onload=alert(1)>\"}","{\"reply\":\"a\",\"reply\":\"b\",\"diagram\":null}"))
            assertThrows(Exception.class,() -> ai.validateChat(raw));
    }
    @Test void educationalPlotsAreComputedUsingFixedBackendFunctions() {
        var plot=EducationalPlot.from("graph x^2").orElseThrow();
        assertEquals(41,plot.data().size()); assertEquals(new java.math.BigDecimal("25.000000"),plot.data().getFirst().amount());
        assertTrue(EducationalPlot.from("graph execute server code").isEmpty());
    }
}
