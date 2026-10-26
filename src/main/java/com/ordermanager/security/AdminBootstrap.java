package com.ordermanager.security;

import com.ordermanager.common.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the first admin from env vars on an empty database. There are no default credentials. */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private final AppUserRepository users;
    private final UserService userService;
    private final AppProperties props;

    public AdminBootstrap(AppUserRepository users, UserService userService, AppProperties props) {
        this.users = users;
        this.userService = userService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (isBlank(props.adminUsername()) || isBlank(props.adminPassword())) {
            throw new IllegalStateException("APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD must be set on first start");
        }
        userService.createInitialAdmin(props.adminUsername(), props.adminPassword());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
