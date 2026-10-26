package com.ordermanager.dashboard;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(username = "op", roles = "OPERATOR")
class DashboardControllerIT extends IntegrationTest {

    @BeforeEach
    void setUp() {
        data.truncateAll();
        data.sku("A", 10);
        data.order("E1", Map.of("A", 1));
    }

    @Test
    void rendersTilesAndChart() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Avg time to ship")))
                .andExpect(content().string(containsString("Stock-outs")))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("NEW: 1 order")));
    }

    @Test
    void chartUsesNoInlineStyles() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(content().string(not(containsString("style="))));
    }
}
