"""Offline integrity checks for local, unpublished public-knowledge authoring drafts."""
import argparse
from collections import Counter
from datetime import date
import json
from pathlib import Path
import re
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACK = ROOT / 'data/public-curation/draft-pack-v1.json'
TOPICS = {'liver-cancer', 'breast-cancer', 'lung-cancer', 'colorectal-cancer',
          'cml', 'backend', 'computer-science'}
NONENDORSEMENT = ('The National Cancer Institute (NCI) does not endorse this translation '
                  'and no endorsement by NCI should be inferred.')
SOURCE_HOSTS = {'www.cancer.gov', 'www.postgresql.org', 'www.rfc-editor.org'}
POLICY_HOSTS = SOURCE_HOSTS | {'trustee.ietf.org'}
SLUG = re.compile(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*')
UUID = re.compile(r'\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', re.I)
FORBIDDEN_KEYS = {'conceptId', 'claimId', 'sourceId', 'spaceId', 'recordId', 'proposalId',
                  'baseVersion', 'operation', 'accessToken', 'apiUrl', 'projectLicense'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(value, name, maximum=2000):
    require(isinstance(value, str) and value.strip() and len(value) <= maximum
            and '\0' not in value, f'Invalid {name}')


def checked_date(value):
    require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}', value), 'Invalid date')
    date.fromisoformat(value)


def https(value, hosts):
    text(value, 'source/policy URL', 2048)
    parsed = urlsplit(value)
    require(parsed.scheme == 'https' and parsed.hostname in hosts and not parsed.username
            and not parsed.password and parsed.port is None and not parsed.query,
            'Expected primary-source HTTPS URL without credentials/query')


def draft(value):
    require(value.get('draftState') == 'DRAFT' and value.get('published') is False,
            'Every authoring item must remain DRAFT and unpublished')
    if 'independentReview' in value:
        require(value['independentReview'] == 'NOT_REVIEWED', 'Independent review cannot be asserted')


def indexed(values, name, maximum):
    require(isinstance(values, list) and 0 < len(values) <= maximum, f'Invalid {name} count')
    result = {}
    for value in values:
        require(isinstance(value, dict), f'Invalid {name} item')
        slug = value.get('slug')
        require(isinstance(slug, str) and SLUG.fullmatch(slug) and slug not in result,
                f'Invalid or duplicate {name} slug')
        result[slug] = value
    return result


def no_canonical_payload(value):
    if isinstance(value, dict):
        require(not FORBIDDEN_KEYS.intersection(value), 'Canonical/import/credential field forbidden')
        for child in value.values():
            no_canonical_payload(child)
    elif isinstance(value, list):
        for child in value:
            no_canonical_payload(child)
    elif isinstance(value, str):
        require(not UUID.search(value), 'Resolved domain UUID forbidden in authoring drafts')


def validate_pack(pack):
    require(isinstance(pack, dict) and pack.get('format') == 'knowledge-brain-public-curation-drafts'
            and pack.get('version') == 1, 'Unknown authoring format')
    no_canonical_payload(pack)
    draft(pack)
    require(pack.get('independentReview') == 'NOT_REVIEWED'
            and pack.get('canonicalResolution') == 'UNRESOLVED'
            and pack.get('explicitProposalRequired') is True
            and pack.get('projectLicenseAssigned') is False, 'Draft review/publication boundary changed')
    checked_date(pack.get('createdAt'))
    require(pack.get('language') == 'ko', 'Expected Korean factual paraphrases')
    expected_limits = dict(maxStatements=32, maxTerms=64, maxSources=16,
                           maxDerivedWordsPerSource=180, quotedSourceWords=0,
                           includesImages=False, includesCodeExcerpts=False,
                           includesTreatmentRecommendations=False, includesDoses=False)
    require(pack.get('limits') == expected_limits, 'Declared authoring bounds changed')
    policies = indexed(pack.get('rightsPolicies'), 'policy', 3)
    require(set(policies) == {'nci-text', 'postgresql-documentation', 'ietf-rfc-facts'},
            'Expected the three inspected rights policies')
    for policy in policies.values():
        https(policy.get('url'), POLICY_HOSTS)
        checked_date(policy.get('checkedAt'))
        text(policy.get('noteKo'), 'rights evidence')
        text(policy.get('restrictionKo'), 'rights restriction')
    require(policies['nci-text'].get('nonendorsement') == NONENDORSEMENT,
            'NCI translation nonendorsement must be preserved verbatim')
    sources = indexed(pack.get('sources'), 'source', 16)
    for source in sources.values():
        draft(source)
        require(source.get('independentReview') == 'NOT_REVIEWED', 'Source review remains a draft')
        https(source.get('url'), SOURCE_HOSTS)
        checked_date(source.get('retrievedAt'))
        require(source['retrievedAt'] <= pack['createdAt'], 'Source retrieval postdates the pack')
        require(source.get('rightsPolicySlug') in policies, 'Source rights policy missing')
        for key in ('title', 'publisher', 'inspectedSections', 'rightsNoteKo'):
            text(source.get(key), key)
    terms = indexed(pack.get('terms'), 'term', 64)
    starter = json.loads((ROOT / 'data/public-starter/wikidata-metadata-v1.json').read_text(encoding='utf-8'))
    labels = {item['en']: item['ko'] for item in starter['items']}
    seen_labels = set()
    for term in terms.values():
        draft(term)
        require(term.get('canonicalResolution') == 'UNRESOLVED', 'Term resolution must be explicit later')
        require(term.get('domain') in {'MEDICINE', 'DEVELOPMENT'}
                and term.get('role') in {'TOPIC', 'SUBJECT', 'PREDICATE'}, 'Invalid term domain/role')
        for key in ('titleKo', 'titleEn'):
            text(term.get(key), key, 200)
        label = (term['domain'], term['titleKo'])
        require(label not in seen_labels, 'Duplicate term label: inspect reuse instead')
        seen_labels.add(label)
        hint = term.get('reuseHint', {})
        text(hint.get('instructionKo'), 'term reuse instruction')
        if hint.get('existingEnglishLabel') is not None:
            require(hint.get('catalog') == 'data/public-starter/wikidata-metadata-v1.json'
                    and labels.get(hint['existingEnglishLabel']) == term['titleKo'],
                    'Existing starter label must be reused exactly as a lookup hint')
    topics = pack.get('topics')
    require(isinstance(topics, list) and set(topics) == TOPICS and len(topics) == len(TOPICS),
            'All seven agreed topics must be covered once')
    require(all(terms.get(topic, {}).get('role') == 'TOPIC' for topic in topics), 'Topic term missing')
    statements = indexed(pack.get('statements'), 'statement', 32)
    coverage, words = Counter(), Counter()
    for item in statements.values():
        draft(item)
        require(item.get('independentReview') == 'NOT_REVIEWED', 'Claim review cannot be asserted')
        topic = item.get('topicSlug')
        require(topic in TOPICS, 'Unknown statement topic')
        coverage[topic] += 1
        for key, role in (('subjectSlug', {'TOPIC', 'SUBJECT'}), ('predicateSlug', {'PREDICATE'})):
            term = terms.get(item.get(key), {})
            require(term.get('role') in role and term.get('domain') == terms[topic]['domain'],
                    'Dangling or cross-domain statement term')
        obj = item.get('object', {})
        require(set(obj) == {'type', 'value'} and obj['type'] == 'STRING', 'Draft literal object required')
        text(obj['value'], 'literal object', 1000)
        for key in ('statementKo', 'scopeNotesKo'):
            text(item.get(key), key, 500)
        notes = item.get('qualifierNotesKo')
        require(isinstance(notes, list) and 0 < len(notes) <= 3, 'Explicit bounded qualifier notes required')
        for note in notes:
            text(note, 'qualifier note', 300)
        evidence = item.get('evidence', {})
        source = sources.get(evidence.get('sourceSlug'))
        require(source is not None, 'Missing statement source')
        for evidence_key, source_key in (('sourceUrl', 'url'), ('sourceTitle', 'title'),
                                         ('retrievedAt', 'retrievedAt'), ('rightsPolicySlug', 'rightsPolicySlug')):
            require(evidence.get(evidence_key) == source[source_key], 'Statement/source evidence mismatch')
        for key in ('section', 'evidenceNoteKo', 'rightsNoteKo'):
            text(evidence.get(key), key, 1000)
        derived = [item['statementKo'], obj['value'], item['scopeNotesKo'], *notes, evidence['evidenceNoteKo']]
        words[source['slug']] += sum(len(part.split()) for part in derived)
    require(set(coverage) == TOPICS, 'At least one statement per agreed topic required')
    require(set(words) == set(sources), 'Unused source metadata must be removed or explained separately')
    require(all(count <= 180 for count in words.values()), 'Per-source derived wording exceeds 180 words')
    return dict(state='DRAFT', published=False, terms=len(terms), sources=len(sources),
                statements=len(statements), topicCounts=dict(coverage), derivedWordsBySource=dict(words),
                validationScope='Offline integrity only; no factual/legal/independent review or publication')


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def load_pack(path=DEFAULT_PACK):
    data = Path(path).read_bytes()
    require(len(data) <= 131072, 'Draft pack exceeds 128 KiB')
    return json.loads(data.decode('utf-8'), object_pairs_hook=unique_pairs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', nargs='?', type=Path, default=DEFAULT_PACK)
    args = parser.parse_args()
    try:
        print(json.dumps(validate_pack(load_pack(args.path)), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Invalid public-curation draft: {error}\n')


if __name__ == '__main__':
    main()
