-- Where SeatWave's files are (Module 29). The bytes live in the bucket; this table holds the key,
-- the checksum and the size, so a booking or an event can find its file and prove it is the right one.
-- Rule of this table: a row reaches STORED only after the bytes were checked in the bucket, and bytes
-- are deleted only after their row left STORED. Then no STORED row can point at missing bytes unless
-- something outside SeatWave removed them, which the reconciliation job reports.

CREATE TABLE stored_objects (
  -- uuidv7(), as for hold_id and booking_id (Module 18): time-ordered inserts.
  object_id UUID NOT NULL,
  bucket VARCHAR(63) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  purpose VARCHAR(8) NOT NULL,
  event_id BIGINT NOT NULL,
  -- Set for a ticket, empty for a poster. No foreign key: bookings live on their own shard (Module 18).
  booking_id UUID,
  content_type VARCHAR(32) NOT NULL,
  size_bytes BIGINT NOT NULL,
  -- Known when the row is created for a ticket (SeatWave renders it); for a poster, the organizer's
  -- client declares it and the confirmation step checks it against the bytes.
  sha256 CHAR(64) NOT NULL,
  state VARCHAR(8) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  stored_at TIMESTAMPTZ,
  CONSTRAINT stored_objects_pkey PRIMARY KEY (object_id),
  CONSTRAINT one_row_per_object UNIQUE (bucket, object_key),
  CONSTRAINT known_purpose CHECK (purpose IN ('TICKET', 'POSTER')),
  CONSTRAINT ticket_names_its_booking CHECK ((purpose = 'TICKET') = (booking_id IS NOT NULL)),
  CONSTRAINT allowed_content_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
  -- 20,000,000 bytes: the brief's largest poster; a ticket is about 200,000.
  CONSTRAINT size_in_range CHECK (size_bytes BETWEEN 1 AND 20000000),
  CONSTRAINT sha256_format CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT known_state CHECK (state IN ('PENDING', 'STORED', 'DELETING')),
  CONSTRAINT stored_has_a_time CHECK ((state = 'PENDING') = (stored_at IS NULL))
);

-- The reconciliation job reads PENDING rows by age, and a ticket download finds its row by booking.
CREATE INDEX pending_by_age ON stored_objects (created_at) WHERE state = 'PENDING';
CREATE UNIQUE INDEX one_ticket_per_booking ON stored_objects (booking_id) WHERE purpose = 'TICKET';
