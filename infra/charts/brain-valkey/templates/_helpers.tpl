{{- define "brain-valkey.config" -}}
bind 0.0.0.0
protected-mode yes
port 0
tls-port 6379
tls-cert-file /tls/tls.crt
tls-key-file /tls/tls.key
tls-ca-cert-file /tls/ca.crt
tls-auth-clients no
tls-protocols "TLSv1.2 TLSv1.3"
aclfile /acl/users.acl
save ""
appendonly no
maxmemory {{ .Values.maxMemory }}
maxmemory-policy allkeys-lru
maxclients {{ .Values.maxClients }}
timeout 30
tcp-keepalive 15
tcp-backlog 64
databases 1
dir /data
logfile ""
loglevel notice
{{- end -}}
