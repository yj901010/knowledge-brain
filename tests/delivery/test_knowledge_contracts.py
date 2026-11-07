import copy
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
ID = '0199e000-0000-7000-8000-000000000001'
OTHER = '0199e000-0000-7000-8000-000000000002'
WHEN = '2026-11-07T00:00:00Z'


def contract(name):
    return yaml.safe_load((ROOT / 'contracts/openapi' / name).read_text(encoding='utf-8'))


def response(doc, path, method='get'):
    return doc['paths'][path][method]['responses']['200']['content']['application/json']['schema']


def check_shape(doc, schema, value):
    """Check the response-shape keywords used here, without a new validator dependency."""
    if '$ref' in schema:
        target = doc
        for part in schema['$ref'].removeprefix('#/').split('/'):
            target = target[part]
        return check_shape(doc, target, value)
    types = schema.get('type', [])
    if isinstance(types, str):
        types = [types]
    matches = {
        'object': isinstance(value, dict),
        'array': isinstance(value, list),
        'string': isinstance(value, str),
        'integer': type(value) is int,
        'boolean': type(value) is bool,
        'null': value is None,
    }
    if types and not any(matches.get(kind, False) for kind in types):
        raise AssertionError(f'Expected {types}, received {type(value).__name__}')
    if 'const' in schema and (type(value) is not type(schema['const']) or value != schema['const']):
        raise AssertionError('Unexpected constant')
    if 'enum' in schema and value not in schema['enum']:
        raise AssertionError('Unexpected enum value')
    if isinstance(value, dict):
        if not set(schema.get('required', [])).issubset(value):
            raise AssertionError('Missing required response fields')
        properties = schema.get('properties', {})
        if schema.get('additionalProperties') is False and set(value) - set(properties):
            raise AssertionError('Unexpected response fields')
        for key, item in value.items():
            child = properties.get(key, schema.get('additionalProperties', {}))
            if isinstance(child, dict):
                check_shape(doc, child, item)
    if isinstance(value, list):
        if len(value) > schema.get('maxItems', len(value)):
            raise AssertionError('Response page exceeds its bound')
        for item in value:
            check_shape(doc, schema.get('items', {}), item)


class KnowledgeResponseContracts(unittest.TestCase):
    def setUp(self):
        self.public = contract('public-discovery.yaml')
        self.notes = contract('note-concepts.yaml')
        # PublicDiscoveryController.Page/Graph and NoteConceptStore.Snapshot/Revision DTOs.
        self.item = dict(entityId=ID, kind='CLAIM', title='Typed claim', version=2,
                         state='ACTIVE', content=dict(objectType='NUMBER',
                         objectValue='9007199254740993.123456789012345678'),
                         qualifierPins={}, currentContext=True, effectiveLevel='DRAFT',
                         needsReview=False)
        self.page = dict(projectedTotal=1, offset=0, limit=20, nextOffset=None,
                         staleOmitted=0, items=[self.item])
        self.graph = dict(nodes=[self.item], edges=[], truncated=False, staleOmitted=0)
        self.snapshot = dict(noteId=ID, conceptVersion=2,
                             items=[dict(linkId=OTHER, conceptId=OTHER)])
        self.revisions = [dict(revisionId=OTHER, conceptVersion=2, actorId=OTHER,
                               occurredAt=WHEN, snapshot=self.snapshot)]

    def test_public_search_and_graph_accept_their_distinct_response_shapes(self):
        search = response(self.public, '/api/public/spaces/{spaceId}/search')
        graph = response(self.public, '/api/public/spaces/{spaceId}/graph')
        check_shape(self.public, search, self.page)
        check_shape(self.public, graph, self.graph)
        with self.assertRaises(AssertionError):
            check_shape(self.public, search, self.graph)
        with self.assertRaises(AssertionError):
            check_shape(self.public, graph, self.page)

    def test_public_response_fix_preserves_shared_errors_and_canonical_item_fields(self):
        paths = self.public['paths']
        search = paths['/api/public/spaces/{spaceId}/search']['get']['responses']
        graph = paths['/api/public/spaces/{spaceId}/graph']['get']['responses']
        for status in ('400', '401', '404', '503'):
            self.assertEqual(search[status], graph[status])
        for field in ('qualifierPins', 'currentContext', 'effectiveLevel', 'needsReview'):
            broken = copy.deepcopy(self.page)
            del broken['items'][0][field]
            with self.assertRaises(AssertionError):
                check_shape(self.public, response(self.public,
                    '/api/public/spaces/{spaceId}/search'), broken)

    def test_note_concept_history_is_revision_array_and_current_reads_stay_snapshots(self):
        base = '/api/spaces/{spaceId}/notes/{noteId}/concepts'
        history = response(self.notes, base + '/revisions')
        check_shape(self.notes, history, self.revisions)
        for method in ('get', 'put'):
            check_shape(self.notes, response(self.notes, base, method), self.snapshot)
            with self.assertRaises(AssertionError):
                check_shape(self.notes, response(self.notes, base, method), self.revisions)
        with self.assertRaises(AssertionError):
            check_shape(self.notes, history, self.snapshot)

    def test_note_concept_history_requires_audit_metadata_and_bounded_pages(self):
        schema = response(self.notes,
            '/api/spaces/{spaceId}/notes/{noteId}/concepts/revisions')
        for field in ('revisionId', 'conceptVersion', 'actorId', 'occurredAt', 'snapshot'):
            broken = copy.deepcopy(self.revisions)
            del broken[0][field]
            with self.assertRaises(AssertionError):
                check_shape(self.notes, schema, broken)
        with self.assertRaises(AssertionError):
            check_shape(self.notes, schema, self.revisions * 11)


if __name__ == '__main__':
    unittest.main()
