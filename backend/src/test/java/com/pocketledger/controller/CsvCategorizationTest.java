package com.pocketledger.controller;

import com.pocketledger.model.User;
import com.pocketledger.service.TransactionCategorizer;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CsvCategorizationTest {
    @Test void importsAssignCategoriesLocallyAndRetainProvidedValidCategories() throws Exception {
        var owner=new User("private@test.local","hash");
        var rows=new CSVExtractor().extract(new StringReader("name,amount,date,category,description\nCoffee,-4.25,2026-11-01,,cafe\nRent,-1000,2026-11-01,Other,explicit choice\nPayroll,3000,2026-11-02,,salary\nUnknown,-10,2026-11-03,,uncertain\n"),owner);
        assertEquals(java.util.List.of("Groceries","Other","Salary","Other"),rows.stream().map(r -> r.getTransactionType()).toList());
        assertTrue(rows.stream().allMatch(r -> r.getUser()==owner));
    }
    @Test void legacyThreeColumnCsvStillWorksAndOptionalTypeIsNormalized() throws Exception {
        var owner=new User("private@test.local","hash");
        assertEquals("Transport",new CSVExtractor().extract(new StringReader("name,amount,date\nUber,-15,2026-11-01\n"),owner).getFirst().getTransactionType());
        assertEquals("Rent",new CSVExtractor().extract(new StringReader("name,amount,date,type\nUber,-15,2026-11-01,rent\n"),owner).getFirst().getTransactionType());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> new CSVExtractor().extract(new StringReader("name,amount,date,type\nUber,-15,2026-11-01,ADMIN\n"),owner));
    }
    @Test void ambiguousOrUnsupportedLocalRulesStayOther() {
        assertEquals("Other",TransactionCategorizer.suggest("Coffee and parking",null,new BigDecimal("-10")));
        assertEquals("Other",TransactionCategorizer.suggest("Payroll",null,new BigDecimal("-10")));
    }
}
