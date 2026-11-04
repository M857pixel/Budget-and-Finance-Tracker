/*

    This program sends a provided prompt to Mistral, prints the raw JSON response,
    and saves a formatted copy as response.json in the working directory

    Usage:

    1. Go to api directory

    2. Set the MISTRAL_API_KEY environment variable:

            macOS/Linux:   export MISTRAL_API_KEY="your-api-key"

            Windows Command Prompt:   set MISTRAL_API_KEY=your-api-key

   3.   mvn compile
        mvn exec:java -Dexec.mainClass=Mistral -Dexec.args="Example-Query-Goes-Here"

 */


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.io.File;
import java.net.CookieHandler;
import java.net.CookieManager;


public class Mistral {
    private static final String API_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final String MODEL = "ministral-8b-latest";

    private final HttpClient client;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public Mistral() {
        this.apiKey = System.getenv("MISTRAL_API_KEY");

        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);
        this.client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Set the MISTRAL_API_KEY environment variable first."
            );
        }
    }

    public static void main(String[] args) throws Exception {
        Mistral mistral = new Mistral();

        String input;
        if (args.length > 0) {
            input = String.join(" ", args);
        } else {
            input = "user query goes here";
        }


        String aiResponse = mistral.runAI(input);
        JsonNode action = mistral.parseAction(aiResponse);

        String actionType = action.path("action").asText();
        System.out.println("Detected action: " + actionType);

        switch (actionType) {
            case "GET_MOST_RECENT_TRANSACTION_CATEGORY" -> {
                String result = mistral.callBackendAction(actionType);
                System.out.println("Result: " + result);
            }
            case "NONE" -> {
                System.out.println("This appears to be a general question. No backend action required.");
            }
            default -> {
                System.err.println("Unsupported action: " + actionType);
                System.exit(1);
            }
        }

        mistral.saveJsonResponse(aiResponse);
        mistral.printJsonResponse(aiResponse);

    }

    public String runAI(String input) throws Exception {
        String json = buildRequestJson(input);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "Mistral API returned HTTP " + response.statusCode()
                            + ": " + response.body()
            );
        }

        return response.body();
    }

    private String buildRequestJson(String input) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String systemPrompt = """
            Classify the user's finance-related request into a predefined action.
            
            You must return ONLY valid JSON in this exact format:
            {"action":"ACTION_NAME"}
            
            Supported actions:
            - GET_MOST_RECENT_TRANSACTION_CATEGORY: Use when user asks for the category/type of their most recent transaction
            - NONE: Use for all other questions
            
            Rules:
            - Return exactly one of the supported actions
            - Do NOT return code, method names, URLs, or user IDs
            - Do NOT include any text outside the JSON object
            - If uncertain, return NONE
            """;

        return """
            {
              "model": %s,
              "temperature": 0.0,
              "messages": [
                {
                  "role": "system",
                  "content": %s
                },
                {
                  "role": "user",
                  "content": %s
                }
              ]
            }
            """.formatted(
                mapper.writeValueAsString(MODEL),
                mapper.writeValueAsString(systemPrompt),
                mapper.writeValueAsString(input)
        );
    }


    public JsonNode parseAction(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String content = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

        try {
            // Parse the AI's JSON response
            return mapper.readTree(content);
        } catch (Exception e) {
            // Handle bad JSON
            System.err.println("Failed to parse AI response as JSON: " + content);
            // Fallback: treat as NONE action
            return mapper.createObjectNode().put("action", "NONE");
        }
    }

    public String callBackendAction(String action) throws Exception {
        // Get authenticated user ID from session (probably isn't working)
        Long authenticatedUserId = getAuthenticatedUserId();

        if (authenticatedUserId == null) {
            throw new IllegalStateException("No authenticated user ID available");
        }

        return switch (action) {
            case "GET_MOST_RECENT_TRANSACTION_CATEGORY" -> {
                String url = String.format(
                        "http://localhost:8080/api/assistant/users/%d/latest-transaction-category",
                        authenticatedUserId
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() >= 400) {
                    throw new RuntimeException(
                            "Backend returned HTTP " + response.statusCode() + ": " + response.body()
                    );
                }

                yield response.body();
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        };
    }

    private Long getAuthenticatedUserId() {
        // This is a placeholder
        String userIdEnv = System.getenv("AUTHENTICATED_USER_ID");
        if (userIdEnv != null) {
            try {
                return Long.parseLong(userIdEnv);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }



    private void printJsonResponse(String responseBody) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);

        String content = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

        System.out.println(content);
    }

    private void saveJsonResponse(String responseBody) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(responseBody);

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("response.json").toFile(), json);
    }


}
