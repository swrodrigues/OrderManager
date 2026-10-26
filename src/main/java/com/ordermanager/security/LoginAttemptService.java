package com.ordermanager.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Locks an account for 15 minutes after 5 consecutive wrong passwords.
 * Class-level {@code @Transactional} so the event listeners (which call onFailure/onSuccess on {@code this},
 * bypassing the proxy) still run in a transaction.
 */
@Service
@Transactional
public class LoginAttemptService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final Clock clock;

    public LoginAttemptService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @EventListener
    public void on(AuthenticationFailureBadCredentialsEvent event) {
        onFailure(event.getAuthentication().getName());
    }

    @EventListener
    public void on(AuthenticationSuccessEvent event) {
        onSuccess(event.getAuthentication().getName());
    }

    /** Unknown usernames match no row and are ignored. */
    public void onFailure(String username) {
        users.recordFailedLogin(username, MAX_ATTEMPTS, clock.instant().plus(LOCK_DURATION));
    }

    public void onSuccess(String username) {
        users.resetFailedLogins(username);
    }
}
