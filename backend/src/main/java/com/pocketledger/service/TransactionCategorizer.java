package com.pocketledger.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Local rules only: no CSV or transaction text leaves the backend. Uncertain entries remain Other. */
public final class TransactionCategorizer {
    public static final Set<String> CATEGORIES=Set.of("Clothing","Entertainment","Transport","Groceries","Salary","Rent","Other");
    private static final Map<String,Pattern> RULES=Map.of(
        "Clothing",Pattern.compile("\\b(clothes|clothing|apparel|shoes|footwear)\\b"),
        "Entertainment",Pattern.compile("\\b(netflix|spotify|cinema|movie|movies|concert|theatre|theater|gaming)\\b"),
        "Transport",Pattern.compile("\\b(uber|lyft|taxi|bus|transit|fuel|gas|parking|train|metro|chevron)\\b"),
        "Groceries",Pattern.compile("\\b(groceries|grocery|supermarket|kroger|aldi|tesco|coffee|cafe|restaurant|starbucks)\\b|whole foods|trader joe"),
        "Salary",Pattern.compile("\\b(salary|payroll|paycheck|wages)\\b"),
        "Rent",Pattern.compile("\\b(rent|mortgage)\\b")
    );
    private TransactionCategorizer() {}
    public static String normalize(String category) {
        if(category==null) return null;
        return CATEGORIES.stream().filter(c -> c.equalsIgnoreCase(category.strip())).findFirst().orElse(null);
    }
    public static String suggest(String name,String description,BigDecimal amount) {
        String text=(Objects.toString(name,"")+" "+Objects.toString(description,"")).toLowerCase(Locale.ROOT);
        var matches=RULES.entrySet().stream().filter(e -> e.getValue().matcher(text).find())
            .filter(e -> !e.getKey().equals("Salary") || amount!=null && amount.signum()>0)
            .map(Map.Entry::getKey).toList();
        return matches.size()==1 ? matches.getFirst() : "Other";
    }
}
