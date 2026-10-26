package com.ordermanager.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    public static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;

    public UserService(AppUserRepository users, PasswordEncoder encoder, SessionRegistry sessions) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AppUser createUser(String username, String rawPassword, Role role) {
        return create(username, rawPassword, role);
    }

    /** Used once at startup, before anyone can log in. Does nothing if any user exists. */
    @Transactional
    public void createInitialAdmin(String username, String rawPassword) {
        if (users.count() == 0) {
            create(username, rawPassword, Role.ADMIN);
        }
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void setEnabled(long id, boolean enabled, String actingUsername) {
        AppUser u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!enabled && u.getUsername().equals(actingUsername)) {
            throw new IllegalArgumentException("You cannot disable your own account");
        }
        u.setEnabled(enabled);
        if (!enabled) {
            expireSessions(u.getUsername());
        }
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void resetPassword(long id, String rawPassword) {
        checkPassword(rawPassword);
        AppUser u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        expireSessions(u.getUsername());
    }

    /** Logged-in sessions of this user end at their next request. */
    private void expireSessions(String username) {
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username)) {
                sessions.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
            }
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<AppUser> listUsers() {
        return users.findAllByOrderByUsername();
    }

    private AppUser create(String username, String rawPassword, Role role) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("Username must be 3-50 letters, digits, dots, dashes or underscores");
        }
        checkPassword(rawPassword);
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }
        return users.save(new AppUser(username, encoder.encode(rawPassword), role));
    }

    private static void checkPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least 12 characters");
        }
    }
}
