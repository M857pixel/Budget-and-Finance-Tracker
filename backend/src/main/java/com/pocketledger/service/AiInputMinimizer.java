package com.pocketledger.service;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Positive selection of finite concepts, never regex-redacted free text sent to a provider. */
@Component
public class AiInputMinimizer {
    public enum Period { THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, LAST_6_MONTHS, LAST_12_MONTHS, SELECTED_MONTH }
    public enum Category { ALL, Clothing, Entertainment, Transport, Groceries, Salary, Rent, Other }
    public enum Chart { donut, bar, line }
    public record Selection(AnalysisIntent intent, Period period, Category category, Chart chart, YearMonth selectedMonth) {
        public Selection(AnalysisIntent intent, Period period, Category category, Chart chart) {
            this(intent,period,category,chart,null);
        }
        public Selection {
            Objects.requireNonNull(intent); Objects.requireNonNull(period);
            Objects.requireNonNull(category); Objects.requireNonNull(chart);
            if(period==Period.SELECTED_MONTH) Objects.requireNonNull(selectedMonth);
            else if(selectedMonth!=null) throw new IllegalArgumentException("Month requires SELECTED_MONTH");
        }
    }
    public record Prepared(Selection selection, boolean modification, boolean supported) {}
    private static boolean has(String s, String words) {
        return Pattern.compile("\\b(?:" + words + ")\\b").matcher(s).find();
    }
    private static final Pattern NAMED_MONTH=Pattern.compile(
        "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may(?!\\s+(?:i|we|you)\\b)|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|sept|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+(?:of\\s+)?((?:19|20)\\d{2}))?\\b");
    public Prepared prepare(String raw, Selection previous) {
        return prepare(raw,previous,LocalDate.now());
    }
    Prepared prepare(String raw, Selection previous, LocalDate today) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (has(s, "add|create|insert|edit|update|delete|remove|change|transfer|pay|save|set|confirm|execute|drop|truncate|recategorize"))
            return new Prepared(null, true, false);
        if (has(s, "sql|password|credential|token|cookie|connection|server|file|prompt|instruction|ignore|override|another|someone|other users|user_id|userid|account id"))
            return new Prepared(null, false, false);
        AnalysisIntent intent = has(s,"budget|remaining|over budget") ? AnalysisIntent.budget_status
            : has(s,"income|salary|earnings|earned") ? AnalysisIntent.income_trend
            : has(s,"categor(?:y|ies)|breakdown|grocer(?:y|ies)|food|rent|transport|clothing|entertainment") ? AnalysisIntent.spending_by_category
            : has(s,"spend(?:ing)?|spent|expenses?|outgoings") ? AnalysisIntent.monthly_expenses : null;
        var namedMonth=NAMED_MONTH.matcher(s);
        YearMonth selectedMonth=null;
        if(namedMonth.find()) {
            int month=switch(namedMonth.group(1).substring(0,3)) {
                case "jan" -> 1; case "feb" -> 2; case "mar" -> 3; case "apr" -> 4;
                case "may" -> 5; case "jun" -> 6; case "jul" -> 7; case "aug" -> 8;
                case "sep" -> 9; case "oct" -> 10; case "nov" -> 11; case "dec" -> 12;
                default -> throw new IllegalArgumentException("Unsupported month");
            };
            int year=namedMonth.group(2)!=null ? Integer.parseInt(namedMonth.group(2))
                : has(s,"last year") ? today.getYear()-1 : today.getYear();
            if(namedMonth.group(2)==null && !has(s,"this year|last year")
                    && (month>today.getMonthValue() || month==today.getMonthValue() && has(s,"last "+namedMonth.group(1)))) year--;
            selectedMonth=YearMonth.of(year,month);
            if(namedMonth.find() || has(s,"last month|this month|last 6 months|last 12 months")) return new Prepared(null,false,false);
        }
        boolean followup = previous != null && has(s, "what about|how about|same|instead|last month|this month|this year|last year|line|bar|pie|donut|6 months|12 months|all categories|"+NAMED_MONTH.pattern());
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
        // Reject unsupported date expressions rather than silently changing the requested scope.
        String remainingDates=NAMED_MONTH.matcher(s).replaceAll("");
        if (Pattern.compile("\\b(?:19|20)\\d{2}\\b|\\b(?:week|yesterday|today)\\b").matcher(remainingDates).find())
            return new Prepared(null,false,false);
        if(selectedMonth!=null) period=Period.SELECTED_MONTH;
        else if(period==Period.SELECTED_MONTH) selectedMonth=previous.selectedMonth();
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
        return new Prepared(new Selection(intent,period,category,chart,selectedMonth),false,true);
    }
    /** A fixed supported selection cannot accurately represent these explicit date requests. */
    public boolean needsPeriodClarification(String raw) {
        String text=raw.toLowerCase(Locale.ROOT);
        var matcher=NAMED_MONTH.matcher(text);
        int count=0;
        while(matcher.find()) count++;
        return count>1 || has(text,"between|since|after|before [0-9]|weeks?|yesterday|today|all time")
            || has(text,"(?:last|past) (?!6 months|12 months)[0-9]+ (?:months|days)")
            || count>0 && has(text,"last month|this month|last 6 months|last 12 months");
    }
    public static LocalDate[] range(Selection selection,LocalDate today) {
        if(selection.period()==Period.SELECTED_MONTH)
            return new LocalDate[]{selection.selectedMonth().atDay(1),selection.selectedMonth().atEndOfMonth()};
        return range(selection.period(),today);
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
            case SELECTED_MONTH -> throw new IllegalArgumentException("A selected month is required");
        };
    }
}
