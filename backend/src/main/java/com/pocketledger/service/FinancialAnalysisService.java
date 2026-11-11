package com.pocketledger.service;

import com.pocketledger.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.pocketledger.service.AiInputMinimizer.*;

/** Trusted calculations. Receives only the session owner and backend-validated selection. */
@Service
public class FinancialAnalysisService {
    private final TransactionRepository transactions;
    private final MonthlyBudgetRepository budgets;
    public FinancialAnalysisService(TransactionRepository transactions, MonthlyBudgetRepository budgets) {
        this.transactions=transactions; this.budgets=budgets;
    }
    public record Point(String label, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount) {}
    public record Result(String action, String result, Chart chart, String startDate, String endDate,
                         String category, List<Point> data, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal total, boolean empty) {}

    @Transactional(readOnly=true)
    public Result analyze(Long owner, Selection selection) {
        return analyze(owner,selection,LocalDate.now());
    }
    Result analyze(Long owner, Selection s, LocalDate today) {
        if(owner == null || owner<=0) throw new IllegalArgumentException("Session owner required");
        LocalDate[] dates=range(s.period(),today);
        // The database returns aggregates only. No entity names, notes, or histories are needed.
        var rows=transactions.analysisTotals(owner,dates[0],dates[1]);
        TreeMap<String,BigDecimal> values=new TreeMap<>();
        BigDecimal total=BigDecimal.ZERO;
        for(var row: rows) {
            String category=normalizeCategory(row.getCategory());
            if(s.category()!=Category.ALL && !category.equals(s.category().name())) continue;
            BigDecimal amount=s.intent()==AnalysisIntent.income_trend ? row.getIncome() : row.getExpense();
            if(amount==null) amount=BigDecimal.ZERO;
            total=total.add(amount);
            String key=s.intent()==AnalysisIntent.spending_by_category ? category
                : YearMonth.of(row.getYear(),row.getMonth()).toString();
            values.merge(key,amount,BigDecimal::add);
        }
        boolean empty=total.signum()==0;
        String explanation;
        if(s.intent()==AnalysisIntent.budget_status) {
            BigDecimal budget=BigDecimal.ZERO;
            int missing=0;
            for(YearMonth month=YearMonth.from(dates[0]); !month.isAfter(YearMonth.from(dates[1])); month=month.plusMonths(1)) {
                var stored=budgets.findByUser_UserIdAndBudgetMonth(owner,month.atDay(1));
                if(stored.isPresent()) budget=budget.add(stored.get().getBudgetAmount()); else missing++;
            }
            values.clear(); values.put("Budget",budget); values.put("Expenses",total);
            BigDecimal remaining=budget.subtract(total);
            explanation="Budget: "+money(budget)+". Expenses: "+money(total)+". "
                +(remaining.signum()<0 ? "Over budget: "+money(remaining.abs()) : "Remaining: "+money(remaining))+"."
                +(missing>0 ? " No budget is set for "+missing+" month(s); those months count as zero." : "");
            empty=budget.signum()==0 && total.signum()==0;
        } else {
            if(s.intent()!=AnalysisIntent.spending_by_category) {
                for(YearMonth month=YearMonth.from(dates[0]); !month.isAfter(YearMonth.from(dates[1])); month=month.plusMonths(1))
                    values.putIfAbsent(month.toString(),BigDecimal.ZERO);
            } else values.entrySet().removeIf(e -> e.getValue().signum()==0);
            explanation=(s.intent()==AnalysisIntent.income_trend ? "Income" : "Expenses")+": "+money(total)+"."
                +(empty ? " No matching amounts were recorded." : "");
        }
        explanation+=" Period: "+dates[0]+" through "+dates[1]+"."
            +(s.category()!=Category.ALL ? " Category: "+s.category().name()+"." : "")
            +" Income and expenses are counted separately; refunds count as income. Amounts use your ledger currency.";
        var points=values.entrySet().stream().map(e -> new Point(e.getKey(),e.getValue().setScale(2,RoundingMode.HALF_EVEN))).toList();
        return new Result(s.intent().name(),explanation,s.chart(),dates[0].toString(),dates[1].toString(),
            s.category().name(),points,total.setScale(2,RoundingMode.HALF_EVEN),empty);
    }
    private static String normalizeCategory(String category) {
        try { return Category.valueOf(category).name(); } catch(Exception e) { return Category.Other.name(); }
    }
    private static String money(BigDecimal amount) { return amount.setScale(2,RoundingMode.HALF_EVEN).toPlainString(); }
}
