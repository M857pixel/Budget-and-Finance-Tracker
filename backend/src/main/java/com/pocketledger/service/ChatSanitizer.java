package com.pocketledger.service;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** Best-effort DLP for general conversation. Financial records never enter this path. */
@Component
public class ChatSanitizer {
    public record Message(String text, boolean blocked, AiInputMinimizer.Selection context) {
        public Message(String text,boolean blocked) { this(text,blocked,null); }
        public Message withContext(AiInputMinimizer.Selection context) { return new Message(text,blocked,context); }
    }
    private static final Pattern SECRET=Pattern.compile(
        "(?is)(?:password|passwd|pwd|api[ _-]?key|secret|access[ _-]?token|refresh[ _-]?token|authorization|session[ _-]?(?:id|cookie))\\s*(?::|=|is\\b)\\s*\\S+"
        +"|\\b(?:Bearer\\s+\\S+|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)"
        +"|-----BEGIN [A-Z ]*PRIVATE KEY-----|jdbc:|postgres(?:ql)?://"
        +"|\\b\\d{3}-\\d{2}-\\d{4}\\b|(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"
        +"|\\b(?:sk-|mstrl_)[A-Za-z0-9_-]{12,}|\\b[A-Za-z0-9_+/=-]{32,}\\b");
    public Message sanitize(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>1000 || SECRET.matcher(raw).find()) return new Message("",true);
        String text=raw.replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ")
            .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}","[EMAIL]")
            .replaceAll("(?i)https?://[^\\s]+","[URL]")
            .replaceAll("(?i)\\b(?:account|routing|user|customer|transaction)[ _-]?(?:number|id|no)?\\s*(?::|=|#|is\\b)\\s*[^\\s,;]+","[IDENTIFIER]")
            .replaceAll("[$€£]\\s*[0-9][0-9,.]*", "[AMOUNT]")
            .replaceAll("(?i)\\b[0-9][0-9,.]*\\s*(?:USD|EUR|GBP|dollars?|euros?|pounds?)\\b","[AMOUNT]");
        text=text.replaceAll("(?i)\\b(?:transaction|account|routing|user|customer)(?:[ _-]?(?:number|id|no))?\\s*[#:=]?\\s*[0-9]+\\b","[IDENTIFIER]")
            .replaceAll("(?<!\\d)(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?!\\d)","[PHONE]")
            .replaceAll("(?i)\\b(?:my name is|i am named)\\s+[a-z][a-z '-]{0,60}","[NAME]");
        if(Pattern.compile("(?i)\\b(?:my (?:balance|income|salary|savings)|i (?:earn|have|make))\\b").matcher(raw).find())
            text=text.replaceAll("\\b[0-9][0-9,.]*\\b","[AMOUNT]");
        return new Message(text,false);
    }
}
