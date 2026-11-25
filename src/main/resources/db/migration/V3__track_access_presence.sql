ALTER TABLE access_event
    ADD COLUMN IF NOT EXISTS classification VARCHAR(40) NOT NULL DEFAULT 'UNCLASSIFIED'
        CHECK (classification IN ('UNCLASSIFIED', 'NORMAL',
                                  'ENTRY_WHILE_INSIDE', 'EXIT_WHILE_OUTSIDE', 'OUT_OF_ORDER'));

CREATE TABLE IF NOT EXISTS access_presence (
    credential_ref VARCHAR(100) PRIMARY KEY,
    inside BOOLEAN NOT NULL DEFAULT FALSE,
    last_occurred_at TIMESTAMPTZ,
    last_event_id UUID REFERENCES access_event(id) ON DELETE CASCADE,
    CONSTRAINT ck_access_presence_last_event
        CHECK ((last_occurred_at IS NULL) = (last_event_id IS NULL))
);

-- Existing events predate anomaly detection. Seed only the latest known
-- entry/exit for each credential; historical anomalies remain unclassified.
INSERT INTO access_presence (credential_ref, inside, last_occurred_at, last_event_id)
SELECT DISTINCT ON (credential_ref)
       credential_ref,
       event_type = 'ACCESS_GRANTED',
       occurred_at,
       id
FROM access_event
WHERE event_type IN ('ACCESS_GRANTED', 'EXIT_REGISTERED')
ORDER BY credential_ref, occurred_at DESC, received_at DESC, id DESC
ON CONFLICT (credential_ref) DO NOTHING;
