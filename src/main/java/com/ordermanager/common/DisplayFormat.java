package com.ordermanager.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatting helpers for templates, used as {@code ${@fmt.dateTime(order.createdAt)}}. */
@Component("fmt")
public class DisplayFormat {

    private final DateTimeFormatter dateTime;

    public DisplayFormat(AppProperties props) {
        ZoneId zone = ZoneId.of(props.timeZone() == null || props.timeZone().isBlank() ? "UTC" : props.timeZone());
        this.dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
    }

    public String dateTime(Instant instant) {
        return instant == null ? "—" : dateTime.format(instant);
    }

    /** e.g. "3h 05m", "2d 4h", "—" for null. */
    public String duration(Duration d) {
        if (d == null) {
            return "—";
        }
        long minutes = d.toMinutes();
        if (minutes >= 48 * 60) {
            return (minutes / 1440) + "d " + (minutes % 1440) / 60 + "h";
        }
        return String.format("%dh %02dm", minutes / 60, minutes % 60);
    }
}
