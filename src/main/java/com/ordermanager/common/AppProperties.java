package com.ordermanager.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secrets and bootstrap settings, bound from env vars APP_ENC_KEY, APP_ADMIN_USERNAME, APP_ADMIN_PASSWORD,
 * plus APP_TIME_ZONE for displaying timestamps.
 */
@ConfigurationProperties("app")
public record AppProperties(String encKey, String adminUsername, String adminPassword, String timeZone) {
}
