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
    static final String MODEL = "ministral-8b-latest";
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

    public enum Failure { MISSING_KEY, PROVIDER_RATE_LIMITED, PROVIDER_AUTHORIZATION, PROVIDER_UNAVAILABLE, INVALID_RESPONSE }
    public static class Unavailable extends IllegalStateException {
        private final Failure failure;
        public Unavailable(Failure failure) { super("Assistant unavailable"); this.failure=failure; }
        public Failure failure() { return failure; }
    }

    public record Edge(int from,int to) {}
    public record Diagram(java.util.List<String> nodes,java.util.List<Edge> edges) {}
    public record ChatReply(String reply,Diagram diagram,AiInputMinimizer.Selection analysis) {
        public ChatReply(String reply,Diagram diagram) { this(reply,diagram,null); }
    }

    public ChatReply converse(ChatSanitizer.Message message) {
        if(message==null || message.blocked()) throw new IllegalArgumentException("Sanitized message required");
        try { return validateChat(completion(buildChatPayload(message)).path("content").textValue()); }
        catch(Unavailable e) { throw e; }
        catch(Exception e) { throw new Unavailable(Failure.INVALID_RESPONSE); }
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
        catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) { throw new Unavailable(Failure.INVALID_RESPONSE); }
        catch(Exception e) { throw new Unavailable(Failure.PROVIDER_UNAVAILABLE); }
    }
    String buildChatPayload(ChatSanitizer.Message message) throws Exception {
        if(message.blocked()) throw new IllegalArgumentException("Sanitized message required");
        var root=mapper.createObjectNode();
        root.put("model",MODEL); root.put("temperature",0.4); root.put("max_tokens",1600);
        root.putObject("response_format").put("type","json_object");
        var messages=root.putArray("messages");
        messages.addObject().put("role","system").put("content",
            "You are PocketLedger's natural-language assistant. Understand varied wording and follow-up questions; do not require commands. "
            +"Answer general questions naturally. You have no database, backend, files, tools, or credentials. Never claim to have accessed or changed records. "
            +"For questions about the user's actual finances, interpret their request into an optional analysis object. The server will validate it, query only their data, and calculate the real answer. "
            +"Do not invent amounts, balances, transaction histories, SQL, owner IDs, or actions. Never ask for passwords or keys. Redaction markers represent unavailable information. "
            +"Distinguish general advice (analysis null) from requests for the user's recorded financial facts. "
            +"analysis must be null or exactly {\"intent\":\"budget_status\",\"period\":\"SELECTED_MONTH\",\"category\":\"ALL\",\"chart\":\"bar\",\"month\":\"2026-10\"}. "
            +"Allowed intents: spending_by_category, monthly_expenses, budget_status, income_trend. "
            +"Allowed periods: THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, LAST_6_MONTHS, LAST_12_MONTHS, SELECTED_MONTH. "
            +"For SELECTED_MONTH use a YYYY-MM month (1900-2099); for other periods month must be null. Named months without a year mean the most recent occurrence. "
            +"Allowed categories: ALL, Clothing, Entertainment, Transport, Groceries, Salary, Rent, Other. Allowed charts: donut, bar, line; chart may be null when the user does not specify a chart (the server chooses a safe default). "
            +"Budget status always uses category ALL and chart bar. Income trend normally uses line. "
            +"Retain context period/category for follow-ups unless changed. Today is "+java.time.LocalDate.now()+". "
            +"If the question or requested period is ambiguous or unsupported (e.g. a comparison, arbitrary date range, or week), ask a conversational clarification and set analysis null. Do not substitute a different period. "
            +"For category edits explain the preview/confirmation flow; never emit a write operation. Category commands include categorize \"coffee\" as Groceries or autocategorize my transactions. "
            +"You may provide an optional conceptual diagram with up to 12 short node labels and up to 24 edges indexed from 0. No numerical financial chart data or photorealistic images. "
            +"Return a JSON object with reply (natural-language string), optional diagram (null or {\"nodes\":[\"label\"],\"edges\":[{\"from\":0,\"to\":1}]}), and optional analysis. "
            +"No other properties, tools, SQL, HTML/SVG markup, URLs, or code execution. For an analysis request, the reply is just an acknowledgement; the server creates the final explanation.");
        if(message.context()!=null) {
            var context=message.context();
            var approved=mapper.createObjectNode();
            approved.put("intent",context.intent().name()); approved.put("period",context.period().name());
            approved.put("category",context.category().name()); approved.put("chart",context.chart().name());
            if(context.selectedMonth()!=null) approved.put("month",context.selectedMonth().toString()); else approved.putNull("month");
            messages.addObject().put("role","system").put("content","Previous financial selection only: "+mapper.writeValueAsString(approved));
        }
        messages.addObject().put("role","user").put("content",message.text());
        return mapper.writeValueAsString(root);
    }
    ChatReply validateChat(String raw) throws Exception {
        if(raw==null || raw.length()>16000) throw new IllegalArgumentException();
        JsonNode node=mapper.readTree(raw);
        if(!node.isObject() || node.size()<1 || node.size()>3 || !node.path("reply").isTextual()
                || !java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(),0),false)
                    .allMatch(key -> java.util.Set.of("reply","diagram","analysis").contains(key))
                || node.get("reply").textValue().isBlank() || node.get("reply").textValue().length()>8000) throw new IllegalArgumentException();
        Diagram diagram=null;
        JsonNode d=node.path("diagram");
        if(!d.isNull() && !d.isMissingNode()) {
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
        AiInputMinimizer.Selection analysis=null;
        JsonNode request=node.path("analysis");
        if(!request.isNull() && !request.isMissingNode()) analysis=validateAnalysis(request);
        return new ChatReply(node.get("reply").textValue(),diagram,analysis);
    }
    private AiInputMinimizer.Selection validateAnalysis(JsonNode node) {
        if(!node.isObject() || node.size()!=5 || !node.has("month")) throw new IllegalArgumentException();
        for(String key:java.util.List.of("intent","period","category"))
            if(!node.path(key).isTextual()) throw new IllegalArgumentException();
        var intent=AnalysisIntent.valueOf(node.get("intent").textValue());
        var period=AiInputMinimizer.Period.valueOf(node.get("period").textValue());
        var category=AiInputMinimizer.Category.valueOf(node.get("category").textValue());
        AiInputMinimizer.Chart chart;
        if(node.path("chart").isNull()) {
            chart=switch(intent) {
                case spending_by_category -> AiInputMinimizer.Chart.donut;
                case income_trend -> AiInputMinimizer.Chart.line;
                default -> AiInputMinimizer.Chart.bar;
            };
        } else {
            if(!node.path("chart").isTextual()) throw new IllegalArgumentException();
            chart=AiInputMinimizer.Chart.valueOf(node.get("chart").textValue());
        }
        java.time.YearMonth month=null;
        if(!node.get("month").isNull()) {
            if(!node.get("month").isTextual() || !node.get("month").textValue().matches("(?:19|20)[0-9]{2}-(?:0[1-9]|1[0-2])")) throw new IllegalArgumentException();
            month=java.time.YearMonth.parse(node.get("month").textValue());
        }
        if(intent==AnalysisIntent.budget_status && (category!=AiInputMinimizer.Category.ALL || chart!=AiInputMinimizer.Chart.bar))
            throw new IllegalArgumentException();
        return new AiInputMinimizer.Selection(intent,period,category,chart,month);
    }
}
