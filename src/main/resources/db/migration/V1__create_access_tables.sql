CREATE TABLE IF NOT EXISTS device (
    id UUID PRIMARY KEY,
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS access_event (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL
        REFERENCES device(id),
    external_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    credential_ref VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_device_external_event
        UNIQUE (device_id, external_event_id),

    CONSTRAINT ck_event_type
        CHECK (event_type IN (
            'ACCESS_GRANTED',
            'ACCESS_DENIED',
            'EXIT_REGISTERED'
        ))
);

CREATE INDEX IF NOT EXISTS idx_access_event_device_time
    ON access_event (device_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_access_event_received_at
    ON access_event (received_at DESC);
