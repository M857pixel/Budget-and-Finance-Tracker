package com.pocketledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.time.YearMonth;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pocketledger.service.AiInputMinimizer.*;

class NaturalLanguageInterpretationTest {
    final AiAssistantService assistant=new AiAssistantService("private-api-key",mock(HttpClient.class));
    final ObjectMapper mapper=new ObjectMapper();
    @Test void optionalPresentationDoesNotPreventNaturalTextAnswers() throws Exception {
        assertEquals("Hello!",assistant.validateChat("{\"reply\":\"Hello!\"}").reply());
        assertNull(assistant.validateChat("{\"reply\":\"Hello!\"}").analysis());
    }
    @Test void naturalQuestionCanSelectOnlyAnApprovedReadAnalysis() throws Exception {
        var reply=assistant.validateChat("""
            {"reply":"I'll check your October budget.","analysis":{
              "intent":"budget_status","period":"SELECTED_MONTH","month":"2026-10","category":"ALL","chart":"bar"}}
            """);
        assertEquals(AnalysisIntent.budget_status,reply.analysis().intent());
        assertEquals(YearMonth.of(2026,10),reply.analysis().selectedMonth());
    }
    @Test void actualObservedModelReplyWithNoDiagramAndNullChartUsesASafePresentationDefault() throws Exception {
        var reply=assistant.validateChat("""
            {"reply":"I'll check your food spending last month.","analysis":{
              "intent":"spending_by_category","period":"LAST_MONTH","category":"Groceries","chart":null,"month":null}}
            """);
        assertEquals(AnalysisIntent.spending_by_category,reply.analysis().intent());
        assertEquals(Period.LAST_MONTH,reply.analysis().period());
        assertEquals(Category.Groceries,reply.analysis().category());
        assertEquals(Chart.donut,reply.analysis().chart());
    }
    @Test void financialContextContainsOnlyApprovedSelectionNeverRecordsResultsOrCredentials() throws Exception {
        var previous=new Selection(AnalysisIntent.budget_status,Period.SELECTED_MONTH,Category.ALL,Chart.bar,YearMonth.of(2026,10));
        var message=new ChatSanitizer().sanitize("And how much room did I have left then?").withContext(previous);
        var root=mapper.readTree(assistant.buildChatPayload(message));
        String context=root.get("messages").get(1).get("content").textValue();
        assertTrue(context.contains("2026-10"));assertTrue(context.contains("budget_status"));
        for(String forbidden:java.util.List.of("amount","balance","userId","email","password","private-api-key")) assertFalse(context.contains(forbidden));
        assertFalse(root.has("tools"));
    }
    @Test void modelCannotSupplySqlOwnerWritesArbitraryRangesOrInvalidMonths() {
        String base="{\"reply\":\"Checking\",\"analysis\":{\"intent\":\"budget_status\",\"period\":\"SELECTED_MONTH\",\"month\":\"2026-10\",\"category\":\"ALL\",\"chart\":\"bar\"}}";
        for(String invalid:java.util.List.of(
            base.replace("budget_status","DELETE_TRANSACTION"),
            base.replace("SELECTED_MONTH","SQL"),
            base.replace("2026-10","2026-13"),
            base.replace("2026-10","3000-10"),
            base.replace("\"month\":\"2026-10\"","\"month\":null"),
            base.replace("\"chart\":\"bar\"","\"chart\":\"bar\",\"userId\":99"),
            base.replace("\"chart\":\"bar\"","\"chart\":\"bar\",\"sql\":\"SELECT * FROM users\""),
            base.replace("\"category\":\"ALL\"","\"category\":\"Groceries\""),
            base.replace("SELECTED_MONTH","THIS_MONTH")))
            assertThrows(Exception.class,() -> assistant.validateChat(invalid));
    }
}
