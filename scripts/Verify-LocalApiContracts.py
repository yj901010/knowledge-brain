"""Targeted live response-shape regressions using the existing contract test helpers.

Creates only fresh private fixtures, reads public discovery and revokes its new
guest session. Requires the local source cluster, trusted HTTPS forward and the
already reviewed PyYAML operator dependency; never logs credentials or payloads.
"""
import base64
import copy
import hashlib
import http.cookiejar
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(root / 'scripts'), str(root / 'tests/delivery')]
from local_recovery import Cluster, now, write_json
from test_knowledge_contracts import contract, response, check_shape
from test_space_contract import admits_view

c = Cluster(root / '.local/kubeconfig.yaml', 'knowledge-brain')
uid = c.uid()
image = c.get('deployment', 'gateway-service', 'brain-apps')['spec']['template']['spec']['containers'][0]['image']
ca = base64.b64decode(c.get('secret', 'brain-guest-api-trust', 'brain-data')['data']['ca.crt']).decode()
tls = ssl.create_default_context(cadata=ca)
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies), urllib.request.HTTPSHandler(context=tls))
base = 'https://localhost:8443'

def call(method, path, body=None, etag=None):
    headers = {'Origin':base, 'Accept':'application/json'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if etag is not None:
        headers['If-Match'] = etag
    request = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        result = client.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        result = error
    with result:
        data = result.read(2*1024*1024+1)
        assert len(data) <= 2*1024*1024
        return result.status, json.loads(data) if data else None, result.headers.get('ETag')

cases = []
assert call('POST', '/api/identity/guest-session', {})[0] == 201
try:
    for _ in range(60):
        status, personal, _ = call('GET', '/api/spaces/personal')
        if status == 200:
            break
        assert status == 404
        time.sleep(.5)
    else:
        raise RuntimeError('Fresh private Space did not reach its owning service')
    space_doc = contract('space-guest.yaml')
    gateway_doc = contract('gateway-guest.yaml')
    for doc in [space_doc, gateway_doc]:
        assert admits_view(doc['components']['schemas']['Space'], personal)
    status, visible, _ = call('GET', '/api/spaces/'+personal['spaceId'])
    assert status == 200 and visible == personal
    for doc in [space_doc, gateway_doc]:
        assert admits_view(doc['components']['schemas']['VisibleSpace'], visible)
    cases += ['personal-route-remains-private-owner', 'visible-by-id-real-personal-response']

    status, catalog, _ = call('GET', '/api/spaces/public')
    assert status == 200
    public_space = next(p['spaceId'] for p in catalog['items'] if p['domain'] == 'DEVELOPMENT')
    public_doc = contract('public-discovery.yaml')
    status, page, _ = call('GET', '/api/public/spaces/'+public_space+'/search?q=java&limit=20')
    assert status == 200
    check_shape(public_doc, response(public_doc, '/api/public/spaces/{spaceId}/search'), page)
    old = copy.deepcopy(public_doc)
    old_schema = response(old, '/api/public/spaces/{spaceId}/search')
    old_schema['$ref'] = '#/components/schemas/GraphView'
    try:
        check_shape(old, old_schema, page)
    except AssertionError:
        cases.append('real-search-page-rejects-old-graph-contract')
    else:
        raise AssertionError('Old GraphView contract unexpectedly accepted a real search page')
    status, graph, _ = call('GET', '/api/public/spaces/'+public_space+'/graph?limit=20&depth=2')
    assert status == 200
    check_shape(public_doc, response(public_doc, '/api/public/spaces/{spaceId}/graph'), graph)
    cases.append('real-public-graph-retains-graph-contract')

    private = '/api/spaces/'+personal['spaceId']
    status, concept, _ = call('POST', private+'/concepts', {'title':'V1 contract fixture concept', 'description':'Private response-shape fixture'})
    assert status == 201
    status, note, _ = call('POST', private+'/notes', {'title':'V1 contract fixture Note', 'markdown':'Private response-shape fixture'})
    assert status == 201
    link_path = private+'/notes/'+note['noteId']+'/concepts'
    status, snapshot, etag = call('GET', link_path)
    assert status == 200
    status, saved, _ = call('PUT', link_path, {'conceptIds':[concept['conceptId']]}, etag)
    assert status == 200 and len(saved['items']) == 1
    status, revisions, _ = call('GET', link_path+'/revisions?limit=10')
    assert status == 200 and revisions and revisions[0]['snapshot'] == saved
    notes_doc = contract('note-concepts.yaml')
    check_shape(notes_doc, response(notes_doc, '/api/spaces/{spaceId}/notes/{noteId}/concepts'), saved)
    check_shape(notes_doc, response(notes_doc, '/api/spaces/{spaceId}/notes/{noteId}/concepts/revisions'), revisions)
    try:
        check_shape(notes_doc, {'$ref':'#/components/schemas/ReferenceSnapshot'}, revisions)
    except AssertionError:
        cases.append('real-nonempty-link-history-rejects-old-snapshot-contract')
    else:
        raise AssertionError('Old Snapshot contract unexpectedly accepted real revision history')
    cases.append('real-link-snapshot-contract-unchanged')
finally:
    assert call('POST', '/api/identity/guest-session/revoke', {})[0] == 204
    assert call('GET', '/api/spaces/personal')[0] == 401

assert len(cases) == 6 and c.uid() == uid
assert c.get('deployment', 'gateway-service', 'brain-apps')['spec']['template']['spec']['containers'][0]['image'] == image
proof = dict(state='PASSED', capturedAt=now(), clusterUid=uid, gatewayImage=image, verifiedCA=True,
    cases=cases, count=len(cases), freshPrivateFixtureOnly=True, newSessionRevoked=True,
    publicWrites=0, credentialsOrPayloadsLogged=False,
    scope='Targeted response-shape keywords and regression controls, not a general OpenAPI/JSON Schema validator',
    contractSha256={name:hashlib.sha256((root/'contracts/openapi'/name).read_bytes()).hexdigest()
                    for name in ['space-guest.yaml','gateway-guest.yaml','public-discovery.yaml','note-concepts.yaml']})
write_json(root/'.local/v1-audit/contract-runtime.json', proof)
print('PASS six current-image trusted-HTTPS response contract checks; original defective shapes rejected; fresh guest revoked')
