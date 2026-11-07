"""Read-only compatibility check for inventoried historical tag-based installers.

These entry points predate digest-pinned application releases. This is not an
image verifier or a deployment tool; it must run before their first side effect.
"""
from pathlib import Path
import re

import yaml


# Explicit inventory: infrastructure installers and unrelated values are excluded.
INSTALLER_SERVICES = {
    'Install-LocalBrainNetwork.py': ('space', 'knowledge', 'content', 'brain-query'),
    'Install-LocalClaimActivity.py': ('knowledge', 'activity', 'gateway'),
    'Install-LocalClaimCitations.py': ('knowledge', 'gateway'),
    'Install-LocalClaimQualifiers.py': ('knowledge', 'gateway'),
    'Install-LocalClaimUsages.py': ('knowledge', 'gateway'),
    'Install-LocalConceptNotes.py': ('content', 'gateway'),
    'Install-LocalExternalMappings.py': ('knowledge', 'gateway'),
    'Install-LocalGraphAttachments.py': ('gateway',),
    'Install-LocalGraphConceptLinks.py': ('gateway',),
    'Install-LocalGraphEditor.py': ('gateway',),
    'Install-LocalGraphJournal.py': ('gateway',),
    'Install-LocalGraphLibrary.py': ('gateway',),
    'Install-LocalGraphLinks.py': ('gateway',),
    'Install-LocalGraphMarkdown.py': ('gateway',),
    'Install-LocalGraphMetadata.py': ('content', 'gateway'),
    'Install-LocalGraphRetirement.py': ('gateway',),
    'Install-LocalGraphSearch.py': ('gateway',),
    'Install-LocalGroupWorkspace.py': ('gateway',),
    'Install-LocalKnowledgeWorkspace.py': ('gateway',),
    'Install-LocalNoteConcepts.py': ('space', 'knowledge', 'content', 'gateway'),
    'Install-LocalNoteGraphProjector.py': ('brain-query',),
    'Install-LocalNoteProjector.py': ('brain-query',),
    'Install-LocalPersonalConceptAliases.py': ('knowledge', 'gateway'),
    'Install-LocalPersonalConceptClaims.py': ('knowledge', 'gateway'),
    'Install-LocalPersonalConceptEditor.py': ('gateway',),
    'Install-LocalPersonalConceptLibrary.py': ('gateway',),
    'Install-LocalPersonalConceptRetirement.py': ('gateway',),
    'Install-LocalPersonalSources.py': ('knowledge', 'gateway'),
    'Install-LocalPublicConceptReferences.py': ('knowledge', 'gateway'),
    'Install-LocalPublicContributions.py': ('space', 'knowledge', 'gateway'),
    'Install-LocalPublicDiscovery.py': ('space', 'knowledge', 'brain-query', 'gateway'),
    'Install-LocalPublicKnowledgeWorkbench.py': ('space', 'knowledge', 'gateway'),
    'Install-LocalPublicRoadmaps.py': ('space', 'learning', 'gateway'),
    'Install-LocalRegisteredLogin.py': ('space', 'identity', 'gateway'),
    'Install-LocalRoadmapAdoptions.py': ('learning', 'gateway'),
    'Install-LocalRoadmapRatings.py': ('space', 'learning', 'gateway'),
    'Install-LocalRoadmaps.py': ('space', 'learning'),
    'Install-LocalRoadmapWorkspace.py': ('gateway',),
    'Install-LocalSourceCitations.py': ('knowledge', 'gateway'),
    'Install-LocalTypedClaims.py': ('brain-query', 'knowledge', 'gateway'),
    'Install-LocalWorkspaceDesign.py': ('gateway',),
    'Install-LocalWorkspaceFlow.py': ('gateway',),
}


def selected_services(installer, options=None):
    """Match existing service selectors after entry-point argument validation."""
    name = Path(installer).name
    if name not in INSTALLER_SERVICES:
        raise ValueError('Historical installer is not inventoried: ' + name)
    options = options or {}
    if name == 'Install-LocalRoadmaps.py':
        for flag, service in (
            ('brain_query_only', 'brain-query'), ('notification_only', 'notification'),
            ('planning_only', 'planning'), ('gateway', 'gateway'),
            ('activity_only', 'activity'), ('space_only', 'space'),
            ('media_only', 'media'), ('knowledge_only', 'knowledge'),
            ('content_only', 'content'), ('learning_only', 'learning'),
        ):
            if options.get(flag):
                return (service,)
    elif name == 'Install-LocalBrainNetwork.py':
        if options.get('gateway'):
            return ('gateway',)
        if options.get('enable'):
            return ('brain-query',)
    elif name == 'Install-LocalClaimUsages.py' and options.get('gateway_only'):
        return ('gateway',)
    return INSTALLER_SERVICES[name]


class _UniqueKeyLoader(yaml.SafeLoader):
    """Do not let a duplicate key hide a digest or malformed image mapping."""

    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in result:
                raise ValueError('duplicate YAML mapping key')
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def _image_values(path):
    # Existing PyYAML dependency only; no shell, metadata builder or cluster calls.
    raw = path.read_bytes()
    if len(raw) > 1024 * 1024:
        raise ValueError('values file exceeds 1 MiB')
    values = yaml.load(raw.decode('utf-8-sig'), Loader=_UniqueKeyLoader)
    if not isinstance(values, dict) or not isinstance(values.get('image'), dict):
        raise ValueError('image must be a YAML mapping')
    image = values['image']
    for key in ('repository', 'tag', 'digest'):
        if key in image and not isinstance(image[key], str):
            raise ValueError('image.' + key + ' must be a string')
    if 'repository' in image and not image['repository'].strip():
        raise ValueError('image.repository must not be blank')
    digest = image.get('digest', '')
    if digest and not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise ValueError('image.digest must be empty or a complete sha256 digest')
    if digest and image.get('tag'):
        raise ValueError('image.digest and image.tag cannot both be set')
    return image


def require_tag_baseline(installer, root, options=None):
    """Refuse pinned/malformed affected values before any historical operation."""
    name = Path(installer).name
    guidance = (
        'Use docs/java-runtime.md (Upgrade sequence): Run-DeliveryCI.py --images, '
        'scan the exact archives, import those archives on both nodes, then prepare '
        'reviewed image-only Helm changes with image.digest and an empty image.tag. '
        'For release-lock/GitOps staging use docs/delivery.md. '
        'Do not clear a current digest to reuse a historical installer. '
        'See docs/local-feature-installers.md for the inventory and limits.'
    )
    pinned = []
    for service in selected_services(name, options):
        relative = Path('infra/environments/local') / (service + '-values.yaml')
        try:
            image = _image_values(Path(root) / relative)
        except (OSError, UnicodeError, ValueError, TypeError, yaml.YAMLError) as error:
            # Do not print YAML contents, which may contain private configuration.
            reason = str(error) if isinstance(error, ValueError) else type(error).__name__
            raise SystemExit(
                f'{name}: historical installer stopped before side effects; '
                f'invalid affected image values in {relative.as_posix()} ({reason}). '
                + guidance
            ) from None
        if image.get('digest'):
            pinned.append(relative.as_posix())
    if pinned:
        raise SystemExit(
            f'{name}: historical tag-based installer stopped before side effects; '
            'digest-pinned application values: ' + ', '.join(pinned) + '. ' + guidance
        )
