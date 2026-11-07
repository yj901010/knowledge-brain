"""Publish or verify the immutable Knowledge-owned Citation v1 event contract."""
import hashlib
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

issuer = 'https://brain-keycloak.brain-data.svc:8443/realms/brain-platform'
registry = 'http://brain-registry.brain-events.svc:8080/apis/registry/v3'
tls = ssl.create_default_context(cafile='/trust/tls.crt')


def request(url, method='GET', body=None, credential=None, content_type='application/json'):
    headers = {'Content-Type': content_type}
    if credential:
        headers['Authorization'] = 'Bearer ' + credential
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method),
            context=tls, timeout=15,
        ) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def token(client, key):
    secret = Path('/clients/' + key).read_text().strip()
    status, data = request(
        issuer + '/protocol/openid-connect/token', 'POST',
        urllib.parse.urlencode(dict(
            grant_type='client_credentials', client_id=client, client_secret=secret,
        )).encode(), content_type='application/x-www-form-urlencoded',
    )
    assert status == 200, 'Token request failed for ' + client + ': status ' + str(status)
    credential = json.loads(data)['access_token']
    print('PASS token issued: ' + client, flush=True)
    return credential


publisher = token('registry-publisher', 'publisher')
reader = token('registry-reader', 'reader')
content = Path('/scripts/knowledge_citation_v2.proto').read_bytes().decode('utf-8')
status, _ = request(registry + '/groups', 'POST', dict(groupId='knowledge'), publisher)
assert status in (200, 201, 409), 'Group create status ' + str(status)
url = registry + '/groups/knowledge/artifacts/citation-events-v2'
status, data = request(url + '/versions/1/content', credential=reader)
if status == 404:
    body = dict(
        artifactId='citation-events-v2', artifactType='PROTOBUF',
        firstVersion=dict(version='1', content=dict(
            content=content, contentType='application/x-protobuf',
        )),
    )
    status, _ = request(registry + '/groups/knowledge/artifacts', 'POST', body, publisher)
    assert status in (200, 201), 'Contract registration status ' + str(status)
elif status == 200:
    assert data.decode('utf-8') == content, 'Existing immutable Citation contract differs'
else:
    raise AssertionError('Contract read status ' + str(status))

for rule, config in [('VALIDITY', 'FULL'), ('COMPATIBILITY', 'FULL_TRANSITIVE')]:
    status, _ = request(url + '/rules', 'POST', dict(ruleType=rule, config=config), publisher)
    assert status in (200, 201, 204, 409), 'Rule create ' + rule + ' status ' + str(status)
    status, data = request(url + '/rules/' + rule, credential=reader)
    assert status == 200 and json.loads(data)['config'] == config, 'Rule verification ' + rule
    print('PASS ' + rule + '=' + config, flush=True)

status, data = request(url + '/versions/1/content', credential=reader)
assert status == 200 and data.decode('utf-8') == content, 'Immutable contract final verification'
print('PASS knowledge/citation-events-v2 version 1 SHA256=' + hashlib.sha256(data).hexdigest(), flush=True)
