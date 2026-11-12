import io
import json
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
from ghcr_private import require_private_package


IMAGE = 'ghcr.io/example/knowledge-brain/gateway-service'
ENDPOINT = 'https://api.github.com/user/packages/container/knowledge-brain%2Fgateway-service'
TOKEN = 'temporary-visibility-test-token'
PRIVATE_PACKAGE = {
    'owner': {'login': 'example'},
    'name': 'knowledge-brain/gateway-service',
    'package_type': 'container',
    'visibility': 'private',
}


class PackageResponse(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body)
        self.status = status

    def getcode(self):
        return self.status

    def geturl(self):
        return ENDPOINT


class PrivatePackageTests(unittest.TestCase):
    def setUp(self):
        self.make_opener = self.enterContext(patch('urllib.request.build_opener'))
        self.open = self.make_opener.return_value.open

    def registry_error(self, status, code):
        body = io.BytesIO(json.dumps({'errors': [{'code': code, 'message': TOKEN}]}).encode('utf-8'))
        return urllib.error.HTTPError('https://ghcr.io/token', status, 'Failure', {}, body)

    def reply(self, payload, status=200, pull_result=None):
        metadata = PackageResponse(json.dumps(payload).encode('utf-8'), status)
        self.open.side_effect = [metadata, pull_result or self.registry_error(401, 'UNAUTHORIZED')]

    def assert_denied(self, repository=IMAGE, token=TOKEN):
        with self.assertRaises(ValueError) as error:
            require_private_package(repository, token)
        self.assertNotIn(TOKEN, str(error.exception))

    def test_exact_private_package_checks_scoped_pull_without_sending_the_pat(self):
        self.reply(PRIVATE_PACKAGE)
        require_private_package(IMAGE, TOKEN)

        self.assertEqual(2, self.open.call_count)
        request, pull = [item.args[0] for item in self.open.call_args_list]
        self.assertEqual(ENDPOINT, request.full_url)
        self.assertEqual('GET', request.get_method())
        self.assertEqual('Bearer ' + TOKEN, request.get_header('Authorization'))
        self.assertGreater(self.open.call_args.kwargs['timeout'], 0)
        url = urlsplit(pull.full_url)
        self.assertEqual(('https', 'ghcr.io', '/token'), (url.scheme, url.netloc, url.path))
        self.assertEqual({'service': ['ghcr.io'], 'scope': ['repository:example/knowledge-brain/gateway-service:pull']},
                         parse_qs(url.query))
        self.assertIsNone(pull.get_header('Authorization'))
        self.assertNotIn(TOKEN, pull.full_url)

    def test_both_requests_refuse_redirects(self):
        self.reply(PRIVATE_PACKAGE)
        require_private_package(IMAGE, TOKEN)

        for construction, call in zip(self.make_opener.call_args_list, self.open.call_args_list, strict=True):
            handler, = construction.args
            self.assertIsInstance(handler, urllib.request.HTTPRedirectHandler)
            result = handler.redirect_request(call.args[0], None, 302, 'Found', {}, 'https://other.invalid/')
            self.assertIsNone(result)

    def test_accepts_explicit_registry_denied(self):
        self.reply(PRIVATE_PACKAGE, pull_result=self.registry_error(403, 'DENIED'))
        require_private_package(IMAGE, TOKEN)

    def test_private_metadata_requires_an_unambiguous_registry_denial(self):
        for result in (
            PackageResponse(json.dumps({'token': TOKEN}).encode('utf-8')),
            urllib.error.URLError('service unavailable'),
            self.registry_error(302, 'DENIED'),
            self.registry_error(401, 'INVALID'),
            self.registry_error(403, 'TOOMANYREQUESTS'),
            self.registry_error(404, 'NAME_UNKNOWN'),
            self.registry_error(429, 'TOOMANYREQUESTS'),
            self.registry_error(500, 'DENIED'),
            urllib.error.HTTPError('https://ghcr.io/token', 401, 'Invalid', {}, io.BytesIO(b' ' * 4097)),
        ):
            with self.subTest(result=type(result).__name__, status=getattr(result, 'code', None)):
                self.reply(PRIVATE_PACKAGE, pull_result=result)
                self.assert_denied()

    def test_rejects_public_internal_and_mismatched_metadata(self):
        for change in (
            {'visibility': 'public'},
            {'visibility': 'internal'},
            {'visibility': None},
            {'owner': {'login': 'different'}},
            {'owner': None},
            {'name': 'knowledge-brain/identity-service'},
            {'package_type': 'npm'},
        ):
            with self.subTest(change=change):
                self.reply(dict(PRIVATE_PACKAGE, **change))
                self.assert_denied()

    def test_rejects_missing_permission_package_redirect_and_server_error(self):
        for status in (302, 401, 403, 404, 429, 500):
            with self.subTest(status=status):
                body = io.BytesIO(json.dumps({'message': TOKEN}).encode('utf-8'))
                self.open.side_effect = urllib.error.HTTPError(ENDPOINT, status, 'Failure', {}, body)
                self.assert_denied()

    def test_rejects_non_success_response_even_if_metadata_claims_private(self):
        self.reply(PRIVATE_PACKAGE, status=201)
        self.assert_denied()

    def test_rejects_bad_metadata_and_unreachable_api(self):
        for body in (b'not JSON', b'[]', b'\xff', b' ' * 1_048_577):
            with self.subTest(body_size=len(body)):
                self.open.return_value = PackageResponse(body)
                self.assert_denied()
        self.open.side_effect = urllib.error.URLError('service unavailable')
        self.assert_denied()

    def test_rejects_unexpected_destination_before_request(self):
        for image in (
            'https://' + IMAGE,
            IMAGE.replace('ghcr.io', 'ghcr.io.other.invalid'),
            IMAGE + ':latest',
            IMAGE + '@sha256:' + 'a' * 64,
            IMAGE + '?visibility=private',
            IMAGE.replace('knowledge-brain', '%2F..'),
        ):
            with self.subTest(image=image):
                self.assert_denied(image)
        self.assert_denied(token='')
        self.open.assert_not_called()


if __name__ == '__main__':
    unittest.main()
