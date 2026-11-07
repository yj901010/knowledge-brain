CREATE TABLE public_knowledge_space (
  space_id uuid PRIMARY KEY REFERENCES space(space_id),
  domain_key text NOT NULL UNIQUE CHECK (domain_key IN ('MEDICINE','DEVELOPMENT')),
  title text NOT NULL
);
INSERT INTO space(space_id,kind,visibility,owner_principal_id,created_at)
VALUES ('0199e000-0000-7000-8000-000000000001','PUBLIC','PUBLIC','0199e000-0000-7000-8000-0000000000ff',CURRENT_TIMESTAMP),
       ('0199e000-0000-7000-8000-000000000002','PUBLIC','PUBLIC','0199e000-0000-7000-8000-0000000000ff',CURRENT_TIMESTAMP);
INSERT INTO public_knowledge_space(space_id,domain_key,title)
VALUES ('0199e000-0000-7000-8000-000000000001','MEDICINE','의학'),
       ('0199e000-0000-7000-8000-000000000002','DEVELOPMENT','개발 · Computer Science');
