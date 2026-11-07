CREATE TABLE public_discovery_inbox(event_id uuid PRIMARY KEY,wire_hash text NOT NULL,received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE public_discovery_source(
 space_id uuid NOT NULL,entity_id uuid NOT NULL,kind text NOT NULL,version bigint NOT NULL,
 state text NOT NULL,title text NOT NULL,content jsonb NOT NULL,qualifier_pins jsonb NOT NULL,
 snapshot_hash text NOT NULL,PRIMARY KEY(space_id,entity_id)
);
CREATE INDEX public_discovery_kind_idx ON public_discovery_source(space_id,kind,entity_id);
CREATE SEQUENCE public_discovery_sequence;
CREATE TABLE public_discovery_work(space_id uuid NOT NULL,entity_id uuid NOT NULL,sequence bigint NOT NULL,PRIMARY KEY(space_id,entity_id));
CREATE TABLE public_discovery_control(id integer PRIMARY KEY CHECK(id=1),available boolean NOT NULL);
INSERT INTO public_discovery_control VALUES(1,false);

CREATE UNIQUE INDEX public_discovery_global_entity ON public_discovery_source(entity_id);
