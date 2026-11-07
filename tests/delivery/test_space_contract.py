"""Response-shape regressions using the existing pinned PyYAML CI dependency."""
import re
import unittest
import uuid
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/openapi"
SPACE_ID = "0199e000-0000-7000-8000-000000000001"


def admits_view(schema, value):
    """Check only the four-string Space shape and its Personal privacy condition."""
    if not isinstance(value, dict) or not set(schema["required"]).issubset(value):
        return False
    properties = schema["properties"]
    if schema.get("additionalProperties") is False and set(value) - properties.keys():
        return False
    for key, item in value.items():
        rule = properties.get(key, {})
        if rule.get("type") == "string" and not isinstance(item, str):
            return False
        if "enum" in rule and item not in rule["enum"]:
            return False
        if "const" in rule and item != rule["const"]:
            return False
        if rule.get("format") == "uuid":
            try:
                uuid.UUID(item)
            except (ValueError, TypeError, AttributeError):
                return False
    condition = schema.get("if", {}).get("properties", {}).get("kind", {})
    if "const" in condition and value.get("kind") == condition["const"]:
        visibility = schema["then"]["properties"]["visibility"]["const"]
        if value.get("visibility") != visibility:
            return False
    return True


class SpaceReadContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.documents = {
            name: yaml.safe_load((CONTRACTS / name).read_text(encoding="utf-8"))
            for name in ("space-guest.yaml", "gateway-guest.yaml", "identity-login.yaml")
        }

    def schema(self, name, path):
        document = self.documents[name]
        response = document["paths"][path]["get"]["responses"]["200"]
        reference = response["content"]["application/json"]["schema"]["$ref"]
        self.assertTrue(reference.startswith("#/components/schemas/"))
        return document["components"]["schemas"][reference.rsplit("/", 1)[1]]

    def view(self, kind="GROUP", visibility="PRIVATE", role="MEMBER"):
        return dict(spaceId=SPACE_ID, kind=kind, visibility=visibility, role=role)

    def test_visible_contract_matches_canonical_space_and_membership_values(self):
        migration = (ROOT / "services/space-service/src/main/resources/db/migration/V1__personal_space_inbox_outbox.sql").read_text()
        for name in ("space-guest.yaml", "gateway-guest.yaml"):
            with self.subTest(contract=name):
                schema = self.schema(name, "/api/spaces/{spaceId}")
                self.assertEqual("object", schema["type"])
                self.assertEqual({"spaceId", "kind", "visibility", "role"}, set(schema["required"]))
                self.assertEqual(set(schema["required"]), set(schema["properties"]))
                self.assertIs(False, schema["additionalProperties"])
                for field in ("kind", "visibility", "role"):
                    check = re.search(r"\b" + field + r" text NOT NULL[^\n]*CHECK \(" + field + r" IN \(([^)]+)\)\)", migration)
                    self.assertIsNotNone(check, field)
                    self.assertEqual(set(re.findall(r"'([^']+)'", check[1])), set(schema["properties"][field]["enum"]))
                self.assertEqual({"properties": {"kind": {"const": "PERSONAL"}}}, schema["if"])
                self.assertEqual({"properties": {"visibility": {"const": "PRIVATE"}}}, schema["then"])

    def test_group_owner_member_and_viewer_responses_are_admitted(self):
        for name in ("space-guest.yaml", "gateway-guest.yaml"):
            for role in ("OWNER", "MEMBER", "VIEWER"):
                with self.subTest(contract=name, role=role):
                    fixture = self.view(role=role)
                    self.assertTrue(admits_view(self.schema(name, "/api/spaces/{spaceId}"), fixture))
                    self.assertFalse(admits_view(self.schema(name, "/api/spaces/personal"), fixture))

    def test_other_declared_model_views_are_admitted_without_an_authorization_claim(self):
        fixtures = [self.view("PERSONAL", role="OWNER"), self.view("PUBLIC", "PUBLIC", "REVIEWER"), self.view("ORGANIZATION", role="ADMIN")]
        for name in ("space-guest.yaml", "gateway-guest.yaml"):
            for fixture in fixtures:
                with self.subTest(contract=name, fixture=fixture):
                    self.assertTrue(admits_view(self.schema(name, "/api/spaces/{spaceId}"), fixture))

    def test_personal_route_remains_private_owner_only(self):
        for name in ("space-guest.yaml", "gateway-guest.yaml"):
            with self.subTest(contract=name):
                schema = self.schema(name, "/api/spaces/personal")
                self.assertEqual(["PERSONAL"], schema["properties"]["kind"]["enum"])
                self.assertEqual(["PRIVATE"], schema["properties"]["visibility"]["enum"])
                self.assertEqual(["OWNER"], schema["properties"]["role"]["enum"])
                self.assertTrue(admits_view(schema, self.view("PERSONAL", role="OWNER")))
                self.assertFalse(admits_view(schema, self.view("PERSONAL", "PUBLIC", "OWNER")))
                self.assertFalse(admits_view(schema, self.view("PERSONAL", role="VIEWER")))
        account_schema = self.documents["identity-login.yaml"]["components"]["schemas"]["Space"]
        for field, value in (("kind", "PERSONAL"), ("visibility", "PRIVATE"), ("role", "OWNER")):
            self.assertEqual(value, account_schema["properties"][field]["const"])

    def test_visible_view_rejects_unknown_missing_extra_or_private_default_violations(self):
        invalid = [self.view("UNKNOWN"), self.view(visibility="UNLISTED"), self.view(role="ROOT"), self.view("PERSONAL", "PUBLIC", "OWNER"), dict(self.view(), spaceId="bad-id"), dict(self.view(), role=1), dict(self.view(), state="ACTIVE")]
        missing = self.view()
        del missing["role"]
        invalid.append(missing)
        for name in ("space-guest.yaml", "gateway-guest.yaml"):
            for fixture in invalid:
                with self.subTest(contract=name, fixture=fixture):
                    self.assertFalse(admits_view(self.schema(name, "/api/spaces/{spaceId}"), fixture))

    def test_gateway_and_direct_visible_schemas_stay_aligned(self):
        self.assertEqual(self.schema("space-guest.yaml", "/api/spaces/{spaceId}"), self.schema("gateway-guest.yaml", "/api/spaces/{spaceId}"))

    def test_gateway_note_concept_history_uses_the_canonical_response(self):
        response = self.documents["gateway-guest.yaml"]["paths"]["/api/spaces/{spaceId}/notes/{noteId}/concepts/revisions"]["get"]["responses"]["200"]
        self.assertEqual({"$ref": "./note-concepts.yaml#/paths/~1api~1spaces~1{spaceId}~1notes~1{noteId}~1concepts~1revisions/get/responses/200"}, response)


if __name__ == "__main__":
    unittest.main()
