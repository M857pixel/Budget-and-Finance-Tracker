package com.pocketledger.service;

import com.pocketledger.model.Transaction;
import com.pocketledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.Pattern;

/** A small authenticated application command language, not a model-executable tool. */
@Service
public class CategoryEdits {
    public record Change(Long transactionId,String name,String from,String to) {}
    public record Preview(String confirmationToken,List<Change> changes) {}
    public record Draft(Long owner,long expiresAt,Preview preview) {}
    private final TransactionRepository transactions;
    public CategoryEdits(TransactionRepository transactions) { this.transactions=transactions; }
    public boolean matches(String text) {
        return Pattern.compile("(?i)^(?:please )?(?:categorize|recategorize|autocategorize|auto-categorize)\\b").matcher(text.strip()).find()
            || Pattern.compile("(?i)^(?:please )?(?:change|edit|update|move|set)\\b.*\\b(?:category|categories)\\b").matcher(text.strip()).find();
    }
    @Transactional(readOnly=true)
    public Draft preview(Long owner,String command) {
        boolean auto=command.strip().matches("(?i)(?:please )?(?:auto-?categorize)(?: my)?(?: uncategorized)?(?: transactions| data)?[.!]?");
        var pattern=Pattern.compile("(?i)^(?:please )?(?:categorize|recategorize|change|move|set|update)\\s+(?:transactions?\\s+)?(?:named\\s+)?(?:\\\"([^\\\"]{1,50})\\\"|#([0-9]{1,18}))\\s+(?:category\\s+)?(?:as|to)\\s+([a-z]+)[.!]?$",Pattern.CASE_INSENSITIVE);
        var match=pattern.matcher(command.strip());
        String keyword=null,target=null; Long id=null;
        if(!auto) {
            if(!match.matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Use: categorize \"coffee\" as Groceries, categorize #42 as Transport, or autocategorize my transactions.");
            keyword=match.group(1); id=match.group(2)==null ? null : Long.parseLong(match.group(2));
            target=TransactionCategorizer.normalize(match.group(3));
            if(target==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose one of the seven ledger categories.");
        }
        List<Transaction> rows;
        if(id!=null) rows=transactions.findByUser_UserIdAndTransactionId(owner,id).map(List::of).orElse(List.of());
        else rows=transactions.findByUser_UserIdOrderByTransactionDateDesc(owner);
        var changes=new ArrayList<Change>();
        for(var row:rows) {
            String current=row.getTransactionType();
            if(auto && current!=null && !current.isBlank() && !current.equals("Other")) continue;
            if(keyword!=null && !Objects.toString(row.getTransactionName(),"").toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) continue;
            String suggested=auto ? TransactionCategorizer.suggest(row.getTransactionName(),row.getTransactionDescription(),row.getTransactionAmount()) : target;
            if(auto && suggested.equals("Other") || Objects.equals(current,suggested)) continue;
            changes.add(new Change(row.getTransactionId(),row.getTransactionName(),current,suggested));
            if(changes.size()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"More than 100 matches. Narrow the request before changing categories.");
        }
        return new Draft(owner,System.currentTimeMillis()+300000,new Preview(UUID.randomUUID().toString(),List.copyOf(changes)));
    }
    @Transactional
    public int apply(Long owner,Draft draft,String token) {
        if(draft==null || !Objects.equals(owner,draft.owner()) || !Objects.equals(token,draft.preview().confirmationToken()) || draft.expiresAt()<System.currentTimeMillis())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Preview expired or invalid. Request a new preview.");
        if(draft.preview().changes().isEmpty()) return 0;
        var rows=transactions.lockOwnedCategoryChanges(owner,draft.preview().changes().stream().map(Change::transactionId).toList());
        var byId=new HashMap<Long,Transaction>(); rows.forEach(row -> byId.put(row.getTransactionId(),row));
        for(var change:draft.preview().changes()) {
            var row=byId.get(change.transactionId());
            if(row==null || !Objects.equals(row.getTransactionType(),change.from()) || !TransactionCategorizer.CATEGORIES.contains(change.to()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A transaction changed since the preview. Request a new preview.");
        }
        for(var change:draft.preview().changes()) byId.get(change.transactionId()).updateDetails(null,change.to(),null,null,null);
        transactions.saveAll(rows);
        return rows.size();
    }
}
