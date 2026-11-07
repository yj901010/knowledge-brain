# Markdown preview in the canonical Note editor

The existing Note editor now offers explicit source/preview toggles for saved body,
unsaved draft and selected immutable history revision. Source remains the initial
read view for compatibility; previews never save, restore or rewrite content. Draft
textarea and strong ETag conflict behavior are unchanged. Switching a Note, changing
revision selection, Space/hide/logout or authorization denial clears preview state
with the existing private component lifecycle. No preview preference/content goes
into browser storage or URL query parameters.

Pinned react-markdown 10.1.0 and remark-gfm 4.0.1 render CommonMark and GFM as React
nodes: headings, emphasis, strikethrough, lists, quotes, inline/fenced code, tables and
read-only task checkboxes. These checkboxes are Markdown text presentation, not
Planning-owned Tasks. There is no code execution or syntax highlighter. HTML is
escaped as text, with no raw-HTML/MDX plugin or dangerouslySetInnerHTML. Markdown
Wiki syntax remains literal; canonical stable-ID navigation continues in the Content
link/backlink panel. No second frontend Wiki binding/parser is introduced.

Links permit only explicit absolute HTTP/HTTPS URLs without credentials/control
characters, open a separate tab with noopener/noreferrer and no referrer. Relative,
protocol-relative, javascript, data and other schemes render labels without anchors.
Images render only escaped alt-text placeholders, including declared attachments;
there are no remote image/iframe resource loads or inline file previews. Downloads
continue through the separate authorized attachment UI. Table alignment uses CSS
classes rather than inline styles; existing CSP directives are retained unchanged.

No Content API/schema/event/ownership or service deployment changes. The static
Gateway browser export adds only the reviewed MIT/ISC dependencies described in
markdown-dependency-review.md. Build/import Gateway on both nodes and run
Install-LocalGraphMarkdown.py --helm with the existing Helm executable.
Run-GraphWebValidation.py --public includes preview and existing regressions;
--markdown-only focuses on renderer/source/privacy tests. Evidence:
note-markdown-preview-validation.txt.

Usage: select a Note and choose 본문 미리보기. While editing choose 작성 중 미리보기;
while viewing a revision choose 이력 미리보기. Toggle back to 원문 보기 as needed.
The feature extends the current functional layout; the later dark graph design,
inline media preview, historical restore and richer Wiki-in-body interactions remain
separate work.
