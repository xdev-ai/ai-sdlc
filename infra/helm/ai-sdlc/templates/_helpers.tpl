{{- define "ai-sdlc.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "ai-sdlc.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "ai-sdlc.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "ai-sdlc.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "ai-sdlc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: ai-sdlc
{{- end -}}

{{- define "ai-sdlc.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "ai-sdlc.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Fail rendering when a required deployment input is missing. A chart that installs without a database reference or an
image digest produces a broken release that looks successful, which is worse than a refused install.
*/}}
{{- define "ai-sdlc.requiredImage" -}}
{{- $digest := . -}}
{{- if not $digest -}}
{{- fail "image digests are required: set image.managementServerDigest and image.portalDigest to sha256 digests, never mutable tags" -}}
{{- end -}}
{{- if not (hasPrefix "sha256:" $digest) -}}
{{- fail (printf "image digest %q must be an immutable sha256 digest" $digest) -}}
{{- end -}}
{{- $digest -}}
{{- end -}}

{{- define "ai-sdlc.requiredValue" -}}
{{- $value := index . 0 -}}
{{- $name := index . 1 -}}
{{- if not $value -}}
{{- fail (printf "%s is required" $name) -}}
{{- end -}}
{{- $value -}}
{{- end -}}
