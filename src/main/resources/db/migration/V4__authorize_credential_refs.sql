CREATE TABLE credential_ref (
    value VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE device_credential_ref (
    device_id UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    credential_ref VARCHAR(100) NOT NULL REFERENCES credential_ref(value),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, credential_ref)
);

-- Preserve the authority implied by events already accepted before this migration.
INSERT INTO credential_ref (value)
SELECT DISTINCT credential_ref FROM access_event;

INSERT INTO device_credential_ref (device_id, credential_ref)
SELECT DISTINCT device_id, credential_ref FROM access_event;
