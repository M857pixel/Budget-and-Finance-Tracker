/*
    Basic java file that queries the Mistral API

    Usage:

    export MISTRAL_API_KEY="insert-api-key-here"
    javac Main.java
    java Main

*/


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Mistral {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("MISTRAL_API_KEY");

        String json = """
            {
              "model": "ministral-8b-latest",
              "messages": [
                {
                  "role": "user",
                  "content": "user query goes here"
                }
              ]
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println(response.body());
    }
}
