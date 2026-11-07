-- Brain Query-owned disposable projection state, never canonical domain data.
CREATE SEQUENCE brain_work_sequence;
CREATE TABLE brain_network_inbox(event_id uuid PRIMARY KEY,payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32));
CREATE TABLE brain_network_snapshot(
 kind text NOT NULL CHECK(kind IN ('NOTE','LINKS','NOTE_CONCEPTS','CONCEPT','CLAIM')),
 entity_id uuid NOT NULL,space_id uuid NOT NULL,version bigint NOT NULL CHECK(version>0),
 fingerprint text NOT NULL CHECK(length(fingerprint)=64),data jsonb NOT NULL,
 PRIMARY KEY(kind,entity_id)
);
CREATE TABLE brain_network_relation(
 owner_kind text NOT NULL,owner_id uuid NOT NULL,relation_id uuid NOT NULL,
 role text NOT NULL,target_kind text NOT NULL,target_id uuid NOT NULL,
 PRIMARY KEY(owner_kind,owner_id,relation_id,role),
 FOREIGN KEY(owner_kind,owner_id) REFERENCES brain_network_snapshot(kind,entity_id)
);
CREATE INDEX brain_network_dependents ON brain_network_relation(target_kind,target_id);
CREATE TABLE brain_network_work(
 kind text NOT NULL CHECK(kind IN ('NOTE','CONCEPT','CLAIM')),entity_id uuid NOT NULL,
 sequence bigint NOT NULL CHECK(sequence>0),applied_sequence bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(kind,entity_id),CHECK(applied_sequence>=0 AND applied_sequence<=sequence)
);
CREATE INDEX brain_network_pending ON brain_network_work(kind,entity_id) WHERE applied_sequence<sequence;
