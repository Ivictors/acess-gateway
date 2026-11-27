CREATE TABLE rate_limit_window (
    identity_key VARCHAR(100) NOT NULL,
    route_key VARCHAR(40) NOT NULL,
    window_start BIGINT NOT NULL,
    hits INTEGER NOT NULL CHECK (hits > 0),
    PRIMARY KEY (identity_key, route_key, window_start)
);

CREATE INDEX idx_rate_limit_window_start ON rate_limit_window (window_start);
