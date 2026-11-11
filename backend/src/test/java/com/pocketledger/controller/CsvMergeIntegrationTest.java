package com.pocketledger.controller;

import com.pocketledger.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CsvMergeIntegrationTest {
    private java.util.List<com.pocketledger.model.Transaction> importRows(String csv) throws Exception {
        var upload=new MockMultipartFile("file","bank.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8));
        byte[] normalized=CSVUploadNormalizer.normalize(upload);
        return new CSVExtractor().extract(new StringReader(new String(normalized,StandardCharsets.UTF_8)),new User("owner@isolated.test","hash"));
    }
    @Test void normalizedBankHeadersAndDebitSignsStillReceiveLocalCategories() throws Exception {
        var rows=importRows("Description,Debit,Credit,Posting Date\nCoffee,4.25,,11/1/2026\nPayroll,,1000,11/2/2026\nUber,15,,11/3/2026\n");
        assertEquals(2,rows.size());
        assertEquals(new BigDecimal("-4.25"),rows.getFirst().getTransactionAmount());
        assertEquals("Groceries",rows.getFirst().getTransactionType());
        assertEquals("Transport",rows.getLast().getTransactionType());
        assertEquals("Coffee",rows.getFirst().getTransactionDescription());
    }
    @Test void normalizationPreservesExplicitCategoriesAndDescriptions() throws Exception {
        var rows=importRows("name,amount,date,type,description\nCoffee,-4.25,2026-11-01,Other,Explicit user note\nPayroll,1000,2026-11-02,,salary\n");
        assertEquals("Other",rows.getFirst().getTransactionType());
        assertEquals("Explicit user note",rows.getFirst().getTransactionDescription());
        assertEquals("Salary",rows.getLast().getTransactionType());
    }
    @Test void longNamesAreShortenedWithoutLosingFullOriginalDescriptionOrSplittingUnicode() throws Exception {
        String name="Coffee "+"\uD83D\uDE00".repeat(55);
        var row=importRows("name,amount,date\n"+name+",-4.25,2026-11-01\n").getFirst();
        assertEquals(50,row.getTransactionName().codePointCount(0,row.getTransactionName().length()));
        assertEquals(name,row.getTransactionDescription());
        assertEquals("Groceries",row.getTransactionType());
    }
}
