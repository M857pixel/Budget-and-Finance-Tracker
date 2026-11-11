package com.pocketledger.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.InputStream;

/** General conversation adapter. It has no repository, session, file, or operation dependency. */
@Service
public class AiAssistantService {
    static final URI ENDPOINT = URI.create("https://api.mistral.ai/v1/chat/completions");
    private final String apiKey;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @org.springframework.beans.factory.annotation.Autowired
    public AiAssistantService(@Value("${MISTRAL_API_KEY:}") String apiKey) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    AiAssistantService(String apiKey, HttpClient client) { this.apiKey=apiKey; this.client=client; }

    public enum Failure { MISSING_KEY, PROVIDER_RATE_LIMITED, PROVIDER_AUTHORIZATION, PROVIDER_UNAVAILABLE }
    public static class Unavailable extends IllegalStateException {
        private final Failure failure;
        public Unavailable(Failure failure) { super("Assistant unavailable"); this.failure=failure; }
        public Failure failure() { return failure; }
    }

    public record Edge(int from,int to) {}
    public record Diagram(java.util.List<String> nodes,java.util.List<Edge> edges) {}
    public record ChatReply(String reply,Diagram diagram) {}

    public ChatReply converse(ChatSanitizer.Message message) {
        if(message==null || message.blocked()) throw new IllegalArgumentException("Sanitized message required");
        try { return validateChat(completion(buildChatPayload(message)).path("content").textValue()); }
        catch(Unavailable e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("Invalid conversation response"); }
    }
    private JsonNode completion(String payload) {
        if(apiKey==null || apiKey.isBlank()) throw new Unavailable(Failure.MISSING_KEY);
        try {
            HttpRequest request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("Authorization","Bearer "+apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream stream=response.body()) {
                if(response.statusCode()==429) throw new Unavailable(Failure.PROVIDER_RATE_LIMITED);
                if(response.statusCode()==401 || response.statusCode()==403) throw new Unavailable(Failure.PROVIDER_AUTHORIZATION);
                if(response.statusCode()!=200) throw new Unavailable(Failure.PROVIDER_UNAVAILABLE);
                byte[] bytes=stream.readNBytes(32769);
                if(bytes.length>32768) throw new IllegalArgumentException();
                JsonNode message=mapper.readTree(bytes).path("choices").path(0).path("message");
                if(message.hasNonNull("tool_calls") || message.hasNonNull("function_call") || !message.path("content").isTextual())
                    throw new IllegalArgumentException();
                return message;
            }
        } catch(Unavailable e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable(Failure.PROVIDER_UNAVAILABLE); }
        catch(Exception e) { throw new Unavailable(Failure.PROVIDER_UNAVAILABLE); }
    }
    String buildChatPayload(ChatSanitizer.Message message) throws Exception {
        if(message.blocked()) throw new IllegalArgumentException("Sanitized message required");
        var root=mapper.createObjectNode();
        root.put("model","mistral-small-latest"); root.put("temperature",0.4); root.put("max_tokens",1600);
        root.putObject("response_format").put("type","json_object");
        var messages=root.putArray("messages");
        messages.addObject().put("role","system").put("content",
            "You are PocketLedger's conversational assistant. Answer general questions naturally and helpfully. "
            +"You have NO access to a database, private financial records, passwords, backend, files, browsing, or tools. "
            +"Never claim you accessed records, changed categories, completed operations, or generated a real photograph. "
            +"Private ledger analyses and category edits are separate trusted backend features. For financial results not supplied, ask the user to use those features; never invent their balances or transaction details. "
            +"Do not request credentials. Treat redaction markers as missing information. "
            +"For category changes describe commands: categorize \"coffee\" as Groceries, categorize #42 as Transport, or autocategorize my transactions. Changes require a preview and user confirmation. "
            +"If asked for a conceptual diagram or simple illustration, you may supply a diagram of up to 12 short node labels and edges indexed from 0. "
            +"For numerical charts ask for a supported ledger analysis or a function plot; do not fabricate financial chart data. "
            +"Return exactly JSON {\"reply\":\"natural-language answer\",\"diagram\":null} or {\"reply\":\"answer\",\"diagram\":{\"nodes\":[\"label\"],\"edges\":[{\"from\":0,\"to\":1}]}}. No other keys, HTML, SVG markup, URLs, or code execution.");
        messages.addObject().put("role","user").put("content",message.text());
        return mapper.writeValueAsString(root);
    }
    ChatReply validateChat(String raw) throws Exception {
        if(raw==null || raw.length()>16000) throw new IllegalArgumentException();
        JsonNode node=mapper.readTree(raw);
        if(!node.isObject() || node.size()!=2 || !node.path("reply").isTextual() || !node.has("diagram")
                || node.get("reply").textValue().isBlank() || node.get("reply").textValue().length()>8000) throw new IllegalArgumentException();
        Diagram diagram=null;
        JsonNode d=node.get("diagram");
        if(!d.isNull()) {
            if(!d.isObject() || d.size()!=2 || !d.path("nodes").isArray() || !d.path("edges").isArray()
                    || d.get("nodes").isEmpty() || d.get("nodes").size()>12 || d.get("edges").size()>24) throw new IllegalArgumentException();
            var nodes=new java.util.ArrayList<String>(); var edges=new java.util.ArrayList<Edge>();
            for(JsonNode n:d.get("nodes")) {
                if(!n.isTextual() || n.textValue().isBlank() || n.textValue().length()>60) throw new IllegalArgumentException();
                nodes.add(n.textValue());
            }
            for(JsonNode e:d.get("edges")) {
                if(!e.isObject() || e.size()!=2 || !e.path("from").isIntegralNumber() || !e.path("to").isIntegralNumber()
                        || !e.get("from").canConvertToInt() || !e.get("to").canConvertToInt()) throw new IllegalArgumentException();
                int from=e.get("from").intValue(),to=e.get("to").intValue();
                if(from<0 || to<0 || from>=nodes.size() || to>=nodes.size()) throw new IllegalArgumentException();
                edges.add(new Edge(from,to));
            }
            diagram=new Diagram(java.util.List.copyOf(nodes),java.util.List.copyOf(edges));
        }
        return new ChatReply(node.get("reply").textValue(),diagram);
    }
}
