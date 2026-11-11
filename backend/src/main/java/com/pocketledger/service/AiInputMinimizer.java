package com.pocketledger.service;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Positive selection of finite concepts, never regex-redacted free text sent to a provider. */
@Component
public class AiInputMinimizer {
    public enum Period { THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, LAST_6_MONTHS, LAST_12_MONTHS }
    public enum Category { ALL, Clothing, Entertainment, Transport, Groceries, Salary, Rent, Other }
    public enum Chart { donut, bar, line }
    public record Selection(AnalysisIntent intent, Period period, Category category, Chart chart) {}
    public record Prepared(Selection selection, boolean modification, boolean supported) {}
    private static boolean has(String s, String words) {
        return Pattern.compile("\\b(?:" + words + ")\\b").matcher(s).find();
    }
    public Prepared prepare(String raw, Selection previous) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (has(s, "add|create|insert|edit|update|delete|remove|change|transfer|pay|save|set|confirm|execute|drop|truncate|recategorize"))
            return new Prepared(null, true, false);
        if (has(s, "sql|password|credential|token|cookie|connection|server|file|prompt|instruction|ignore|override|another|someone|other users|user_id|userid|account id"))
            return new Prepared(null, false, false);
        AnalysisIntent intent = has(s,"budget|remaining|over budget") ? AnalysisIntent.budget_status
            : has(s,"income|salary|earnings|earned") ? AnalysisIntent.income_trend
            : has(s,"categor(?:y|ies)|breakdown|grocer(?:y|ies)|food|rent|transport|clothing|entertainment") ? AnalysisIntent.spending_by_category
            : has(s,"spend(?:ing)?|spent|expenses?|outgoings") ? AnalysisIntent.monthly_expenses : null;
        boolean followup = previous != null && has(s, "what about|how about|same|instead|last month|this month|this year|last year|line|bar|pie|donut|6 months|12 months|all categories");
        if (has(s, "last [0-9]+ (?:days?|weeks?)|(?:last|past) (?!6 months|12 months)[0-9]+ months|merchant|named|at|between|since|before|after")
                || has(s, "last week|this week|all time")) return new Prepared(null,false,false);
        if (intent == null && !followup) return new Prepared(null,false,false);
        boolean inherited = intent == null;
        if (inherited) intent = previous.intent();
        Period period = has(s,"last 12 months|past 12 months") ? Period.LAST_12_MONTHS
            : has(s,"last 6 months|past 6 months") ? Period.LAST_6_MONTHS
            : has(s,"last month") ? Period.LAST_MONTH : has(s,"this month") ? Period.THIS_MONTH
            : has(s,"last year") ? Period.LAST_YEAR : has(s,"this year") ? Period.THIS_YEAR
            : previous != null && followup ? previous.period()
            : intent == AnalysisIntent.income_trend ? Period.LAST_6_MONTHS : Period.THIS_MONTH;
        // Explicit unsupported dates must not silently become a different period.
        if (Pattern.compile("\\b(?:19|20)\\d{2}\\b|\\b(?:january|february|march|april|may|june|july|august|september|october|november|december|week|yesterday|today)\\b").matcher(s).find())
            return new Prepared(null,false,false);
        Category category = has(s,"all categories|all spending|all expenses") ? Category.ALL
            : has(s,"grocer(?:y|ies)|food") ? Category.Groceries : has(s,"rent|mortgage") ? Category.Rent
            : has(s,"transport|travel") ? Category.Transport : has(s,"clothing|clothes") ? Category.Clothing
            : has(s,"entertainment") ? Category.Entertainment : has(s,"other category") ? Category.Other
            : has(s,"salary") ? Category.Salary
            : inherited ? previous.category() : Category.ALL;
        Chart chart = has(s,"line") ? Chart.line : has(s,"bar") ? Chart.bar : has(s,"pie|donut") ? Chart.donut
            : inherited ? previous.chart() : intent == AnalysisIntent.spending_by_category ? Chart.donut
            : intent == AnalysisIntent.income_trend ? Chart.line : Chart.bar;
        // A monthly budget covers total spending, never a category's fraction of it.
        if (intent == AnalysisIntent.budget_status) { category = Category.ALL; chart = Chart.bar; }
        return new Prepared(new Selection(intent,period,category,chart),false,true);
    }
    public static LocalDate[] range(Period period, LocalDate today) {
        LocalDate month = today.withDayOfMonth(1);
        return switch (period) {
            case THIS_MONTH -> new LocalDate[]{month,today};
            case LAST_MONTH -> new LocalDate[]{month.minusMonths(1),month.minusDays(1)};
            case THIS_YEAR -> new LocalDate[]{today.withDayOfYear(1),today};
            case LAST_YEAR -> new LocalDate[]{today.minusYears(1).withDayOfYear(1),today.withDayOfYear(1).minusDays(1)};
            case LAST_6_MONTHS -> new LocalDate[]{month.minusMonths(5),today};
            case LAST_12_MONTHS -> new LocalDate[]{month.minusMonths(11),today};
        };
    }
}
