ALTER TABLE events
    ADD COLUMN featured BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN featured_order INTEGER;

ALTER TABLE events
    ADD CONSTRAINT chk_events_featured_order_non_negative
        CHECK (featured_order IS NULL OR featured_order >= 0);

CREATE INDEX idx_events_featured_public
    ON events (featured_order, event_time, id)
    WHERE featured = TRUE AND status = 'PUBLISHED';

COMMENT ON COLUMN events.featured IS 'Whether the event is curated for the homepage carousel';
COMMENT ON COLUMN events.featured_order IS 'Ascending homepage carousel order';
