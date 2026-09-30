package com.pocketledger.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Test the controller with simulated HTTP requests, without connecting to the database.
@WebMvcTest(FrontendController.class)
class FrontendControllerTest {
    @Autowired
    private MockMvc mvc;

    // Check that Add accepts positive, negative, and zero amounts and sends them back.
    @Test
    void acceptsFrontendAmounts() throws Exception {
        for (String amount : new String[]{"12.34", "-5.50", "0"}) {
            mvc.perform(post("/api/test-record")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"amount\":" + amount + "}"));
        }
    }

    // Check that missing amounts and nonnumeric text are rejected.
    @Test
    void rejectsMissingAndInvalidAmounts() throws Exception {
        for (String body : new String[]{"{}", "{\"amount\":null}", "{\"amount\":\"invalid\"}"}) {
            mvc.perform(post("/api/test-record")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // Browsers ask permission before sending JSON; allow requests from the local frontend.
    @Test
    void permitsFrontendPreflight() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mvc.perform(options("/api/test-record")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    // Check the message returned when the Test backend button is clicked.
    @Test
    void checksBackendConnection() throws Exception {
        mvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Connected to PocketLedger backend."));
    }
}
