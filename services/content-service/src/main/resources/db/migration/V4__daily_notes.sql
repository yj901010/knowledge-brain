CREATE TABLE daily_note (
 space_id uuid NOT NULL,
 journal_date date NOT NULL CHECK(journal_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
 note_id uuid NOT NULL UNIQUE,
 timezone text NOT NULL CHECK(length(timezone) BETWEEN 1 AND 100),
 PRIMARY KEY(space_id,journal_date),
 FOREIGN KEY(space_id,note_id) REFERENCES note(space_id,note_id)
);
-- Bindings survive retirement and cannot be reassigned by the application.
CREATE FUNCTION immutable_daily_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Daily note binding is immutable'; END $$;
CREATE TRIGGER immutable_daily_binding BEFORE UPDATE OR DELETE ON daily_note FOR EACH ROW EXECUTE FUNCTION immutable_daily_binding();
