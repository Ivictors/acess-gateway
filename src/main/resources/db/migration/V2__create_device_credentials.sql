CREATE TABLE IF NOT EXISTS device_credential (
    device_id UUID PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMPTZ,
    CONSTRAINT ck_device_token_hash_length CHECK (octet_length(token_hash) = 32)
);
