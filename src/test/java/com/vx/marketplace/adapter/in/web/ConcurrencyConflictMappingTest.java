package com.vx.marketplace.adapter.in.web;

import com.vx.marketplace.application.port.ConcurrencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConcurrencyConflictMappingTest {

    @RestController
    static class Boom {
        @GetMapping("/boom")
        String boom() {
            throw new ConcurrencyConflictException("Concurrent update on listing L, please retry");
        }
    }

    @Test
    void concurrencyConflictIsReportedAs409ProblemDetail() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom()).setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Concurrent update on listing L, please retry"));
    }
}
