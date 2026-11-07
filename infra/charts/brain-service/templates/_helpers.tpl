{{- define "brain-service.image" -}}
{{- $repo := required "image.repository is required" .Values.image.repository -}}
{{- $digest := .Values.image.digest | default "" -}}
{{- $tag := .Values.image.tag | default "" -}}
{{- if $digest -}}
  {{- if $tag -}}{{- fail "set image.digest or image.tag, never both" -}}{{- end -}}
  {{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $digest) -}}{{- fail "image.digest must be a complete sha256 digest" -}}{{- end -}}
  {{- printf "%s@%s" $repo $digest -}}
{{- else -}}
  {{- printf "%s:%s" $repo (required "image.tag or image.digest is required" $tag) -}}
{{- end -}}
{{- end -}}
