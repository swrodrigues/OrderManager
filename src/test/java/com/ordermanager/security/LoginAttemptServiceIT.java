package com.ordermanager.security;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginAttemptServiceIT extends IntegrationTest {

    @Autowired LoginAttemptService attempts;

    /** Review finding 11: concurrent failures must each count (no lost updates), so lockout really is at 5. */
    @Test
    void concurrentFailuresAreAllCounted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 20; round++) {
                data.user("race", "correct-horse-battery", Role.OPERATOR);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        attempts.onFailure("race");
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(jdbc.queryForObject("select failed_attempts from app_user where username='race'", Integer.class))
                        .as("round %d", round).isEqualTo(4);
            }
        } finally {
            pool.shutdown();
        }
        attempts.onFailure("race");
        assertThat(jdbc.queryForObject("select locked_until > now() from app_user where username='race'", Boolean.class)).isTrue();
    }

    /** Review finding 12: disabling a user must end the sessions they already have. */
    @Test
    void disablingAUserEndsTheirSession() throws Exception {
        long id = data.user("it-session", "correct-horse-battery", Role.OPERATOR).getId();
        MvcResult login = mvc.perform(formLogin("/login").user("it-session").password("correct-horse-battery")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/orders").session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/users/{id}/enabled", id).param("enabled", "false")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));

        mvc.perform(get("/orders").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
    }
}
