package com.pocketledger.service;

import java.math.*;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed formulas only; neither model code nor user expressions are evaluated. */
public final class EducationalPlot {
    private EducationalPlot() {}
    public static Optional<FinancialAnalysisService.Result> from(String raw) {
        String s=raw.toLowerCase(Locale.ROOT);
        if(!Pattern.compile("\\b(plot|graph|chart)\\b").matcher(s).find()) return Optional.empty();
        String formula=s.contains("x^2") || s.contains("x²") || s.contains("x squared") ? "x²"
            : s.contains("sin(x)") || s.contains("sine") ? "sin(x)"
            : s.contains("cos(x)") || s.contains("cosine") ? "cos(x)" : null;
        if(formula==null) return Optional.empty();
        var data=new ArrayList<FinancialAnalysisService.Point>();
        for(int i=-20;i<=20;i++) {
            BigDecimal x=BigDecimal.valueOf(i).divide(BigDecimal.valueOf(4));
            BigDecimal y=formula.equals("x²") ? x.multiply(x) : BigDecimal.valueOf(formula.equals("sin(x)") ? Math.sin(x.doubleValue()) : Math.cos(x.doubleValue()));
            data.add(new FinancialAnalysisService.Point(x.toPlainString(),y.setScale(6,RoundingMode.HALF_EVEN)));
        }
        return Optional.of(new FinancialAnalysisService.Result("function_plot","Here is y = "+formula+" for −5 ≤ x ≤ 5. These are calculated function values, not financial records.",
            AiInputMinimizer.Chart.line,null,null,null,List.copyOf(data),null,false));
    }
}
