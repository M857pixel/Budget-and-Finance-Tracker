package com.pocketledger.controller;

import java.util.Map;
import com.pocketledger.service.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.pocketledger.service.AiInputMinimizer.*;

/** General conversation has no database capabilities. Local analyses and confirmed category commands are independent. */
@RestController
@RequestMapping("/api/assistant")
@CrossOrigin(origins={"http://localhost:5173","http://127.0.0.1:5173"},allowCredentials="true")
public class AiAssistantController {
    private static final String CONTEXT="assistant.selection.v2";
    private final AiInputMinimizer minimizer;
    private final AiAssistantService interpreter;
    private final FinancialAnalysisService analysis;
    private final AuthRateLimiter limiter;
    private final ChatSanitizer sanitizer;
    private final CategoryEdits categoryEdits;
    private static final String PENDING="assistant.category-preview.v1";
    public AiAssistantController(AiInputMinimizer minimizer,AiAssistantService interpreter,
            FinancialAnalysisService analysis,AuthRateLimiter limiter,ChatSanitizer sanitizer,CategoryEdits categoryEdits) {
        this.minimizer=minimizer; this.interpreter=interpreter; this.analysis=analysis; this.limiter=limiter;
        this.sanitizer=sanitizer; this.categoryEdits=categoryEdits;
    }
    @PostMapping("/query")
    public Object query(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session==null || !(session.getAttribute("userId") instanceof Long owner) || owner<=0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in.");
        if(body==null || body.size()!=1 || !(body.get("query") instanceof String query)
                || query.isBlank() || query.length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a question of at most 1,000 characters.");
        limiter.check("analysis:"+owner,20,60000);
        ChatSanitizer.Message sanitized=sanitizer.sanitize(query);
        if(sanitized.blocked()) return message("PRIVACY_BLOCKED","Please remove passwords, API keys, tokens, connection strings, and account/card numbers before sending a question.");
        if(query.strip().matches("(?i)(?:cancel|no|never mind)[.!]?")) {
            session.removeAttribute(PENDING); return message("CANCELLED","Category preview cancelled. No records were changed.");
        }
        if(query.strip().matches("(?i)(?:yes|confirm|go ahead)[.!]?")) {
            CategoryEdits.Draft draft=session.getAttribute(PENDING) instanceof CategoryEdits.Draft d ? d : null;
            if(draft==null) return message("NO_PENDING","There is no category preview to confirm.");
            return confirm(Map.of("confirmationToken",draft.preview().confirmationToken()),request);
        }
        if(categoryEdits.matches(query)) {
            session.removeAttribute(PENDING);
            CategoryEdits.Draft draft;
            try { draft=categoryEdits.preview(owner,query); }
            catch(ResponseStatusException e) {
                if(e.getStatusCode().value()==400) return message("CATEGORY_HELP",e.getReason());
                throw e;
            }
            if(draft.preview().changes().isEmpty()) return message("NO_CHANGES","No matching category changes were found. Existing categories were kept; uncertain automatic suggestions stay Other.");
            session.setAttribute(PENDING,draft);
            return new CategoryResponse("CATEGORY_PREVIEW","Review these "+draft.preview().changes().size()+" category changes, then confirm or cancel. Nothing has been changed yet.",draft.preview());
        }
        session.removeAttribute(PENDING);
        if(query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(?:ignore.*instructions|override.*permissions|another.*user[_ ]?id).*"))
            return message("ACCESS_RESTRICTED","I cannot bypass permissions or access another user's account.");
        Selection previous=session.getAttribute(CONTEXT) instanceof Selection selection ? selection : null;
        boolean personalFinance=query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\bmy\\b.*")
            && query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(?:spending|expenses|income|budget|balance|transactions)\\b.*");
        boolean general=query.strip().toLowerCase(java.util.Locale.ROOT).matches("(?s)^(?:what is|what are|explain|how |why |tell me about|write|draw|illustrate|create|generate).*" )
            && !personalFinance
            || query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(?:help me|teach me|advice|tips)\\b.*");
        Prepared prepared=minimizer.prepare(query,previous);
        if(!general && prepared.modification() && query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(?:transactions?|records?|budget|balance|account)\\b.*"))
            return message("READ_ONLY","I can preview category changes. Use the regular authenticated transaction interface for creating/deleting transactions and changing amounts or budgets.");
        if(!general && prepared.supported()) {
            // Local intent selection cannot access anyone else's records, and needs no provider call.
            var result=analysis.analyze(owner,prepared.selection());
            session.setAttribute(CONTEXT,prepared.selection());
            return result;
        }
        if(previous==null && query.strip().matches("(?i)(?:what about|how about) (?:last|this) (?:month|year)[?!.]?"))
            return message("CLARIFICATION_REQUIRED","Which analysis would you like: spending, income, or budget status?");
        if(!general && minimizer.needsPeriodClarification(query) && (personalFinance || prepared.selection()!=null))
            return message("CLARIFICATION_REQUIRED","Please choose one supported period: a named month such as October 2026, this/last month, this/last year, or the last 6/12 months. I have not sent your request or records to Mistral.");
        var educational=EducationalPlot.from(query);
        if(educational.isPresent()) return educational.get();
        try {
            var reply=interpreter.converse(sanitized.withContext(previous));
            if(reply.analysis()!=null) {
                var result=analysis.analyze(owner,reply.analysis());
                session.setAttribute(CONTEXT,reply.analysis());
                return result; // Never send the result back to the model or display its speculative acknowledgement.
            }
            return new ConversationResponse("CONVERSATION",reply.reply(),reply.diagram());
        } catch(AiAssistantService.Unavailable e) { throw e; }
        catch(IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The conversation service is unavailable. No records were changed."); }
    }
    public record CategoryResponse(String action,String result,CategoryEdits.Preview preview) {}
    public record ConversationResponse(String action,String result,AiAssistantService.Diagram diagram) {}

    @PostMapping("/confirm-categories")
    public Object confirm(@RequestBody Map<String,String> body,HttpServletRequest request) {
        var session=request.getSession(false);
        if(session==null || !(session.getAttribute("userId") instanceof Long owner))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in.");
        if(body==null || body.size()!=1 || body.get("confirmationToken")==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A confirmation token is required.");
        synchronized(session) {
            CategoryEdits.Draft draft=session.getAttribute(PENDING) instanceof CategoryEdits.Draft d ? d : null;
            if(draft==null || !draft.preview().confirmationToken().equals(body.get("confirmationToken")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Request a new category preview.");
            session.removeAttribute(PENDING); // Single-use. A failed/stale draft needs a fresh preview.
            int changed=categoryEdits.apply(owner,draft,body.get("confirmationToken"));
            return message("CATEGORIES_UPDATED","Updated "+changed+" transaction categories. No amounts or other transaction fields were changed.");
        }
    }
    @ExceptionHandler(AiAssistantService.Unavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable(AiAssistantService.Unavailable e) {
        String message=switch(e.failure()) {
            case PROVIDER_RATE_LIMITED -> "Mistral is limiting requests. Wait before retrying; check the Mistral account's usage limits if this continues.";
            case MISSING_KEY -> "The backend's Mistral API key is not configured.";
            case PROVIDER_AUTHORIZATION -> "Mistral rejected the backend's API credentials. Check its API key and account access.";
            case PROVIDER_UNAVAILABLE -> "Mistral is temporarily unavailable. Please try again later.";
            case INVALID_RESPONSE -> "I could not interpret that reply safely. Please rephrase your question; no records were changed.";
        };
        return Map.of("code",e.failure().name(),"message",message);
    }
    @DeleteMapping("/context")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(HttpServletRequest request) {
        var session=request.getSession(false);
        if(session==null || !(session.getAttribute("userId") instanceof Long))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in.");
        session.removeAttribute(CONTEXT);
        session.removeAttribute(PENDING);
    }
    private static FinancialAnalysisService.Result message(String action,String text) {
        return new FinancialAnalysisService.Result(action,text,null,null,null,null,java.util.List.of(),null,true);
    }
}
