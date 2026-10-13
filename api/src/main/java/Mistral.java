/*

    This program sends a provided promt to Mistral, prints the raw JSON response,
    and saves a formatted copy as response.json in the working directory

    Usage:

    1. Go to api directory

    2. Set the MISTRAL_API_KEY environment variable:

            macOS/Linux:   export MISTRAL_API_KEY="your-api-key"

            Windows Command Prompt:   set MISTRAL_API_KEY=your-api-key

   3.   mvn compile
        mvn exec:java -Dexec.mainClass=Mistral -Dexec.args="Explain HTTP in one sentence"

 */


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;


public class Mistral {
    private static final String API_URL =
            "https://api.mistral.ai/v1/chat/completions";
    private static final String MODEL = "ministral-8b-latest";

    private final HttpClient client = HttpClient.newHttpClient();
    private final String apiKey;

    public Mistral() {
        this.apiKey = System.getenv("MISTRAL_API_KEY");

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


        String response = mistral.runAI(input);
        System.out.println(response);
        mistral.saveJsonResponse(response);
        mistral.printJsonResponse(response);

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
        return """
            {
              "model": %s,
              "messages": [
                {
                  "role": "user",
                  "content": %s
                }
              ]
            }
            """.formatted(
                mapper.writeValueAsString(MODEL),
                mapper.writeValueAsString(input)
        );
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
