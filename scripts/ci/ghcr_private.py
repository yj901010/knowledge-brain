"""Require an existing private GHCR package before sending an image to it."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request


REPOSITORY = re.compile(r'ghcr\.io/([a-z0-9][a-z0-9-]*)/([a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*)')
MAX_METADATA_BYTES = 1_048_576
MAX_DENIAL_BYTES = 4_096


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


def require_anonymous_pull_denied(repository):
    match = REPOSITORY.fullmatch(repository)
    if not match:
        raise ValueError('Exact GHCR repository required for anonymous pull check')
    owner, name = match.groups()
    query = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': f'repository:{owner}/{name}:pull'})
    request = urllib.request.Request(f'https://ghcr.io/token?{query}', headers={'Accept': 'application/json'})
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=15):
            raise ValueError(f'GHCR did not deny an anonymous pull token for {repository}')
    except urllib.error.HTTPError as exc:
        denial_code = {401: 'UNAUTHORIZED', 403: 'DENIED'}.get(exc.code)
        try:
            with exc:
                body = exc.read(MAX_DENIAL_BYTES + 1) if denial_code else b''
            payload = json.loads(body) if body and len(body) <= MAX_DENIAL_BYTES else None
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            payload = None
        errors = payload.get('errors') if isinstance(payload, dict) else None
        if (denial_code and isinstance(errors, list) and len(errors) == 1 and
                isinstance(errors[0], dict) and errors[0].get('code') == denial_code):
            return
        raise ValueError(f'GHCR anonymous pull denial unverified for {repository} (HTTP {exc.code})') from None
    except OSError:
        raise ValueError(f'GHCR anonymous pull denial check unavailable for {repository}') from None


def require_private_package(repository, token):
    match = REPOSITORY.fullmatch(repository)
    if not match or not token or any(character.isspace() for character in token):
        raise ValueError('Exact GHCR repository and package metadata credential required')
    owner, name = match.groups()
    package = urllib.parse.quote(name, safe='')
    request = urllib.request.Request(
        f'https://api.github.com/user/packages/container/{package}',
        headers={
            'Accept': 'application/vnd.github+json',
            'Authorization': f'Bearer {token}',
            'X-GitHub-Api-Version': '2026-03-10',
        },
    )
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=15) as response:
            if response.status != 200:
                raise ValueError(f'GHCR package metadata unavailable for {repository} (HTTP {response.status})')
            body = response.read(MAX_METADATA_BYTES + 1)
        if len(body) > MAX_METADATA_BYTES:
            raise ValueError(f'GHCR package metadata exceeds size limit for {repository}')
        metadata = json.loads(body)
    except urllib.error.HTTPError as exc:
        raise ValueError(f'GHCR package metadata unavailable for {repository} (HTTP {exc.code})') from None
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError(f'GHCR package metadata unreadable for {repository}') from None

    account = metadata.get('owner') if isinstance(metadata, dict) else None
    login = account.get('login') if isinstance(account, dict) else None
    if not isinstance(login, str) or login.lower() != owner or (
        metadata.get('name'), metadata.get('package_type'), metadata.get('visibility')
    ) != (name, 'container', 'private'):
        raise ValueError(f'GHCR package is not verified private for {repository}')
    require_anonymous_pull_denied(repository)


def main():
    services = json.loads(Path(__file__).with_name('services.json').read_text(encoding='utf-8'))
    names = [item['name'] for item in services]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--service', choices=names)
    args = parser.parse_args()
    token = os.environ.get('GHCR_TOKEN')
    for name in [args.service] if args.service else names:
        require_private_package(f'ghcr.io/{args.repository}/{name}', token)
    print('Verified existing private GHCR package visibility and anonymous pull denial.')


if __name__ == '__main__':
    main()
