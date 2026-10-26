package com.ordermanager.security;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends IntegrationTest {

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void htmxRequestWithoutSessionGets401WithHxRedirect() throws Exception {
        mvc.perform(post("/orders/1/transition").header("HX-Request", "true").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
    }

    /** Real expiry: the session (and the CSRF token stored in it) is gone (review finding C2). */
    @Test
    void expiredSessionOnHtmxPostRedirectsToLogin() throws Exception {
        mvc.perform(post("/orders/1/transition").header("HX-Request", "true")
                        .header("X-CSRF-TOKEN", "token-from-the-old-session")
                        .with(expiredSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
    }

    @Test
    void expiredSessionOnFormPostRedirectsToLogin() throws Exception {
        mvc.perform(post("/orders").param("_csrf", "token-from-the-old-session").with(expiredSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void forbiddenHtmxRequestGetsAMessageInTheFlashArea() throws Exception {
        mvc.perform(post("/admin/skus").header("HX-Request", "true").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("HX-Retarget", "#flash"))
                .andExpect(content().string(containsString("not allowed")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor expiredSession() {
        return request -> {
            request.setRequestedSessionId("expired-session-id");
            request.setRequestedSessionIdValid(false);
            return request;
        };
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotOpenAdmin() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    void bootstrapAdminCanLogIn() throws Exception {
        mvc.perform(formLogin("/login").user("admin").password("test-admin-password-123"))
                .andExpect(authenticated().withRoles("ADMIN"));
    }

    @Test
    void fiveFailuresLockTheAccount() throws Exception {
        data.user("lockme", "correct-horse-battery", Role.OPERATOR);
        for (int i = 0; i < 5; i++) {
            mvc.perform(formLogin("/login").user("lockme").password("wrong-password-x"));
        }
        mvc.perform(formLogin("/login").user("lockme").password("correct-horse-battery"))
                .andExpect(unauthenticated());

        jdbc.update("update app_user set locked_until = now() - interval '1 minute' where username = 'lockme'");
        mvc.perform(formLogin("/login").user("lockme").password("correct-horse-battery"))
                .andExpect(authenticated());
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        data.user("disabled1", "correct-horse-battery", Role.OPERATOR);
        jdbc.update("update app_user set enabled = false where username = 'disabled1'");
        mvc.perform(formLogin("/login").user("disabled1").password("correct-horse-battery"))
                .andExpect(unauthenticated());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "same-origin"));
    }

    @Test
    void loginErrorIsGeneric() throws Exception {
        mvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invalid username or password, or account temporarily locked.")));
    }
}
