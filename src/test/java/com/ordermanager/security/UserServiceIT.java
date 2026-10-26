package com.ordermanager.security;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceIT extends IntegrationTest {

    @Autowired UserService users;
    @Autowired AppUserRepository repo;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void passwordsAreBcryptCost12() {
        users.createUser("bcrypt1", "long-enough-password", Role.OPERATOR);
        assertThat(repo.findByUsername("bcrypt1").orElseThrow().getPasswordHash()).startsWith("$2a$12$");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void rejectsShortPassword() {
        assertThatThrownBy(() -> users.createUser("short1", "11-chars-xx", Role.OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void rejectsDuplicateUsername() {
        users.createUser("dup1", "long-enough-password", Role.OPERATOR);
        assertThatThrownBy(() -> users.createUser("dup1", "long-enough-password", Role.OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCannotDisableSelf() {
        long adminId = repo.findByUsername("admin").orElseThrow().getId();
        assertThatThrownBy(() -> users.setEnabled(adminId, false, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You cannot disable your own account");
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void operatorCannotCreateUsers() {
        assertThatThrownBy(() -> users.createUser("x1", "long-enough-password", Role.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }
}
