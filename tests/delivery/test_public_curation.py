import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('public_curation', ROOT / 'scripts/Validate-PublicCuration.py')
CURATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CURATION)


class PublicCurationIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.pack = CURATION.load_pack()

    def reject(self, mutate):
        broken = copy.deepcopy(self.pack)
        mutate(broken)
        with self.assertRaises(ValueError):
            CURATION.validate_pack(broken)

    def test_reviewable_drafts_cover_all_seven_topics_with_bounded_wording(self):
        result = CURATION.validate_pack(self.pack)
        self.assertEqual(21, result['statements'])
        self.assertEqual(8, result['sources'])
        self.assertEqual({topic: 3 for topic in CURATION.TOPICS}, result['topicCounts'])
        self.assertEqual('DRAFT', result['state'])
        self.assertFalse(result['published'])
        self.assertTrue(all(value <= 180 for value in result['derivedWordsBySource'].values()))

    def test_pack_and_each_item_cannot_claim_publication_or_independent_review(self):
        self.reject(lambda p: p.update(published=True))
        self.reject(lambda p: p.update(explicitProposalRequired=False))
        self.reject(lambda p: p.update(independentReview='EXPERT_VERIFIED'))
        for collection in ('terms', 'sources', 'statements'):
            self.reject(lambda p, key=collection: p[key][0].update(draftState='ACTIVE'))
            self.reject(lambda p, key=collection: p[key][0].update(published=True))

    def test_canonical_ids_import_fields_and_credentials_cannot_enter_the_pack(self):
        self.reject(lambda p: p['statements'][0].update(conceptId='0199e000-0000-7000-8000-000000000001'))
        self.reject(lambda p: p['terms'][0].update(slug='0199e000-0000-7000-8000-000000000001'))
        self.reject(lambda p: p['statements'][0].update(operation='CREATE'))
        self.reject(lambda p: p['sources'][0].update(accessToken='secret'))
        self.reject(lambda p: p.update(projectLicenseAssigned=True))

    def test_slug_reference_domain_and_topic_integrity(self):
        self.reject(lambda p: p['terms'].append(copy.deepcopy(p['terms'][0])))
        self.reject(lambda p: p['statements'][0].update(subjectSlug='missing'))
        self.reject(lambda p: p['statements'][0].update(predicateSlug='atomicity'))
        self.reject(lambda p: p.update(topics=p['topics'][:-1]))
        self.reject(lambda p: p['statements'][0].update(topicSlug='unknown-topic'))

    def test_statement_evidence_retains_exact_source_date_and_rights(self):
        for key, value in (('sourceSlug', 'unknown'), ('sourceUrl', 'https://example.com/'),
                           ('sourceTitle', 'Wrong title'), ('retrievedAt', '2026-11-06'),
                           ('rightsPolicySlug', 'ietf-rfc-facts'), ('section', '')):
            self.reject(lambda p, k=key, v=value: p['statements'][0]['evidence'].update({k: v}))
        self.reject(lambda p: p['sources'][0].update(url='https://user:secret@www.cancer.gov/'))
        self.reject(lambda p: p['sources'][0].update(url='https://example.com/unofficial-summary'))

    def test_nci_nonendorsement_and_existing_term_reuse_cannot_be_lost(self):
        self.reject(lambda p: p['rightsPolicies'][0].update(nonendorsement=None))
        self.reject(lambda p: p['terms'][0]['reuseHint'].update(existingEnglishLabel='unknown label'))
        self.reject(lambda p: p['terms'][0].update(titleKo='간암의 다른 임의 이름'))

    def test_wording_and_item_bounds_reject_accidental_bulk_material(self):
        self.reject(lambda p: p['statements'][0].update(statementKo='추가 단어 ' * 100))
        self.reject(lambda p: p['statements'][0].update(qualifierNotesKo=[]))
        self.reject(lambda p: p['statements'][0]['object'].update(type='CONCEPT'))
        self.reject(lambda p: p['statements'].extend(copy.deepcopy(p['statements'])))

    def test_loader_rejects_duplicate_keys_and_oversize_input(self):
        with patch.object(Path, 'read_bytes', return_value=b'{"published": false, "published": true}'):
            with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
                CURATION.load_pack('draft.json')
        with patch.object(Path, 'read_bytes', return_value=b' ' * 131073):
            with self.assertRaisesRegex(ValueError, '128 KiB'):
                CURATION.load_pack('draft.json')


if __name__ == '__main__':
    unittest.main()
