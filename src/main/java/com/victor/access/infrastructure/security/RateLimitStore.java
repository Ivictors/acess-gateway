package com.victor.access.infrastructure.security;

import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class RateLimitStore {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final int windowSeconds;

    public RateLimitStore(JdbcClient jdbc, Clock clock,
                          @Value("${access.rate-limit.window-seconds:60}") int windowSeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.windowSeconds = windowSeconds;
    }

    public boolean allow(String identity, String route, int limit, int seconds) {
        long window = Math.floorDiv(clock.instant().getEpochSecond(), seconds);
        return jdbc.sql("""
                INSERT INTO rate_limit_window (identity_key, route_key, window_start, hits)
                VALUES (:identity, :route, :window, 1)
                ON CONFLICT (identity_key, route_key, window_start)
                DO UPDATE SET hits = rate_limit_window.hits + 1
                WHERE rate_limit_window.hits < :limit
                RETURNING hits
                """)
                .param("identity", identity).param("route", route)
                .param("window", window).param("limit", limit)
                .query(Integer.class).optional().isPresent();
    }

    public int secondsUntilReset(int seconds) {
        long now = clock.instant().getEpochSecond();
        return (int) (seconds - Math.floorMod(now, seconds));
    }

    public void removeExpired(int seconds) {
        long oldestWindow = Math.floorDiv(clock.instant().getEpochSecond(), seconds) - 2;
        jdbc.sql("DELETE FROM rate_limit_window WHERE window_start < :oldest")
                .param("oldest", oldestWindow).update();
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void removeExpired() {
        removeExpired(windowSeconds);
    }
}
