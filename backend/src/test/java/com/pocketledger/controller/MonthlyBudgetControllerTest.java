package com.pocketledger.controller;

import com.pocketledger.model.MonthlyBudget;
import com.pocketledger.model.User;
import com.pocketledger.repository.MonthlyBudgetRepository;
import com.pocketledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(MonthlyBudgetController.class)
class MonthlyBudgetControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MonthlyBudgetRepository budgets;
    @MockitoBean UserRepository users;
    private static final String URL = "/api/users/7/budget?month=2026-11";
    private static final LocalDate MONTH = LocalDate.of(2026, 11, 1);

    @Test
    void missingBudgetReadsAndUnchangedZeroDoNotCreateRows() throws Exception {
        when(budgets.findByUser_UserIdAndBudgetMonth(7L, MONTH)).thenReturn(Optional.empty());
        mvc.perform(get(URL).sessionAttr("userId", 7L)).andExpect(status().isOk()).andExpect(content().string("0"));
        mvc.perform(put(URL).sessionAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}"))
                .andExpect(status().isOk());
        verify(budgets, never()).save(any());
        verifyNoInteractions(users);
    }

    @Test
    void createsBudgetForSelectedUserAndMonth() throws Exception {
        var user = mock(User.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        mvc.perform(put(URL).sessionAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":125.50}"))
                .andExpect(status().isOk());
        verify(budgets).save(argThat(budget -> budget.getUser() == user && budget.getBudgetMonth().equals(MONTH)
                && budget.getBudgetAmount().compareTo(new BigDecimal("125.50")) == 0));
    }

    @Test
    void updatesExistingBudgetIncludingResetToZero() throws Exception {
        var budget = new MonthlyBudget(mock(User.class), MONTH, new BigDecimal("500"));
        when(budgets.findByUser_UserIdAndBudgetMonth(7L, MONTH)).thenReturn(Optional.of(budget));
        mvc.perform(get(URL).sessionAttr("userId", 7L)).andExpect(content().string("500"));
        mvc.perform(put(URL).sessionAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isOk());
        verify(budgets, never()).save(any());
        mvc.perform(put(URL).sessionAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}"))
                .andExpect(status().isOk());
        verify(budgets).save(budget);
        org.junit.jupiter.api.Assertions.assertEquals(0, budget.getBudgetAmount().signum());
    }

    @Test
    void rejectsUnauthorizedAccessAndInvalidAmountsBeforeDatabaseAccess() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(put(URL).sessionAttr("userId", 8L).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isForbidden());
        for (String amount : new String[]{"null", "-1", "1.001", "10000000000"}) {
            mvc.perform(put(URL).sessionAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(budgets, users);
    }
}
