package com.ordermanager.admin;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(username = "admin", roles = "ADMIN")
class AdminControllerIT extends IntegrationTest {

    @BeforeEach
    void setUp() {
        data.truncateAll();
        jdbc.update("delete from app_user where username like 'it-%'");
    }

    @Test
    void createOperatorAndListIt() throws Exception {
        mvc.perform(post("/admin/users").with(csrf())
                        .param("username", "it-op1").param("password", "long-enough-password").param("role", "OPERATOR"))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("flash", "User it-op1 created"));
        mvc.perform(get("/admin/users")).andExpect(content().string(containsString("it-op1")));
    }

    @Test
    void shortPasswordShowsError() throws Exception {
        mvc.perform(post("/admin/users").with(csrf())
                        .param("username", "it-op2").param("password", "short").param("role", "OPERATOR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("at least 12 characters")));
    }

    @Test
    void cannotDisableSelf() throws Exception {
        long adminId = jdbc.queryForObject("select id from app_user where username='admin'", Long.class);
        mvc.perform(post("/admin/users/{id}/enabled", adminId).with(csrf()).param("enabled", "false"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("You cannot disable your own account")));
    }

    @Test
    void disableAndResetPasswordOfAnotherUser() throws Exception {
        long id = data.user("it-op3", "long-enough-password", com.ordermanager.security.Role.OPERATOR).getId();
        mvc.perform(post("/admin/users/{id}/enabled", id).with(csrf()).param("enabled", "false"))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(jdbc.queryForObject("select enabled from app_user where id=?", Boolean.class, id)).isFalse();
        mvc.perform(post("/admin/users/{id}/password", id).with(csrf()).param("password", "another-long-password"))
                .andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    void createSkuAndAdjustStock() throws Exception {
        mvc.perform(post("/admin/skus").with(csrf())
                        .param("code", "SKU-900").param("name", "Stretch film").param("onHand", "10"))
                .andExpect(redirectedUrl("/admin/skus"));
        long id = jdbc.queryForObject("select id from sku where code='SKU-900'", Long.class);
        mvc.perform(post("/admin/skus/{id}/adjust", id).with(csrf()).param("delta", "5").param("reason", "cycle count"))
                .andExpect(redirectedUrl("/admin/skus"));
        assertThat(jdbc.queryForObject("select on_hand from sku where id=?", Integer.class, id)).isEqualTo(15);
        mvc.perform(get("/admin/skus")).andExpect(content().string(containsString("Stretch film")));
    }

    @Test
    void adjustBelowReservedRejected() throws Exception {
        long id = data.sku("A", 5);
        jdbc.update("update sku set reserved = 3 where id=?", id);
        mvc.perform(post("/admin/skus/{id}/adjust", id).with(csrf()).param("delta", "-3").param("reason", "damaged"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("cannot go below reserved")));
    }

    @Test
    void adjustRequiresReason() throws Exception {
        long id = data.sku("A", 5);
        mvc.perform(post("/admin/skus/{id}/adjust", id).with(csrf()).param("delta", "1").param("reason", " "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("A reason is required")));
    }

    @Test
    void nonNumericDeltaIsABadRequest() throws Exception {
        long id = data.sku("A", 5);
        mvc.perform(post("/admin/skus/{id}/adjust", id).with(csrf()).param("delta", "lots").param("reason", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorForbidden() throws Exception {
        mvc.perform(post("/admin/skus").with(csrf()).param("code", "X").param("name", "X").param("onHand", "1"))
                .andExpect(status().isForbidden());
    }
}
