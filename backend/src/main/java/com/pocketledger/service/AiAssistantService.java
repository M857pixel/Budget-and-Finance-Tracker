/*

    Contains prompt for AI and reads user input and parses AI output.

    Request Format:

     fetch('http://localhost:8080/api/assistant/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ query: 'What was the category of my most recent transaction?' })
    })


    Response Format:

    Object { action: "GET_MOST_RECENT_TRANSACTION_CATEGORY", result: "Clothing" }
    action: "GET_MOST_RECENT_TRANSACTION_CATEGORY"
    result: "Clothing"


 */


package com.pocketledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Classifies a natural language question into one of a fixed set of actions.
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private static final String API_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final String MODEL = "ministral-8b-latest";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    //System Prompt for the AI. Not Final and will be improved.
    private static final String SYSTEM_PROMPT = """
            Classify the user's finance-related request into a predefined action.

            You must return ONLY valid JSON in this exact format:
            {"action":"ACTION_NAME"}

            Supported actions:
            - GET_MOST_RECENT_TRANSACTION_CATEGORY: Use when the user asks for the category/type of their most recent transaction
            - GET_TRANSACTION_SUMMARY: Use when the user asks for aggregated transaction totals by category
            - CURRENT_BALANCE_QUERY: Use when the user asks for their current balance or account balance
            - RECENT_TRANSACTIONS_LIST: Use when the user asks for a list of their recent transactions
            - NONE: Use for all other questions

            Rules:
            - Return exactly one of the supported actions
            - Do NOT return code, method names, URLs, or user IDs
            - Do NOT include any text outside the JSON object
            - If the request doesn't match any supported action, return NONE
            - If uncertain, return NONE
            """;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public AiAssistantService(@Value("${mistral.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * @return one of the supported action names, uppercased. Falls back to "NONE" when the model returns something unparseable.
     * @throws IllegalStateException if the API key is missing or Mistral is unreachable.
     */
    public String classify(String userQuery) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "mistral.api-key is not set (set MISTRAL_API_KEY or mistral.api-key)");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestJson(userQuery)))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Mistral API returned HTTP " + response.statusCode());
            }

            return extractAction(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Mistral", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach Mistral", e);
        }
    }

    private String buildRequestJson(String userQuery) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.put("temperature", 0.0);

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", userQuery);

        return mapper.writeValueAsString(root);
    }

    private String extractAction(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String content = root.path("choices").path(0).path("message").path("content").asText();

        String cleaned = StripAiResponse(content).trim();

        try {
            String action = mapper.readTree(cleaned).path("action").asText("").trim().toUpperCase();
            if (action.isEmpty()) {
                return "NONE";
            } else {
                return action;
            }

        } catch (Exception e) {
            log.warn("Model output was not valid JSON, treating as NONE: {}", content);
            return "NONE";
        }
    }

    /* Cleaning AI output. */
    private static String StripAiResponse(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline == -1) {
            return trimmed.replace("```", "");
        }
        String body = trimmed.substring(firstNewline + 1);
        int closingFence = body.lastIndexOf("```");
        if (closingFence == -1) {
            return body;
        } else {
            return body.substring(0, closingFence);
        }

    }
}