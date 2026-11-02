{{- define "toolkit.env" -}}
- name: TOOLKIT_DB_URL
  value: {{ .Values.database.url | default (printf "jdbc:postgresql://%s-postgres:5432/toolkit" .Release.Name) | quote }}
- name: TOOLKIT_DB_USER
  value: toolkit
- name: TOOLKIT_DB_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.name }}, key: db-password}}
- name: TOOLKIT_CONFIG
  value: /config/toolkit.yaml
- name: TOOLKIT_OIDC_ISSUER
  value: {{ .Values.oidc.issuer | quote }}
- name: TOOLKIT_OIDC_CLIENT
  value: {{ .Values.oidc.clientId | quote }}
- name: TOOLKIT_LLM_KEY
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.name }}, key: llm-key, optional: true}}
- name: TOOLKIT_ZOS_USER
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.name }}, key: zos-user, optional: true}}
- name: TOOLKIT_ZOS_PASS
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.name }}, key: zos-password, optional: true}}
{{- end -}}
{{- define "toolkit.mounts" -}}
- {name: source, mountPath: /data/source, readOnly: true}
- {name: config, mountPath: /config}
- {name: captures, mountPath: /data/captures}
{{- if .Values.target.claimName }}
- {name: target, mountPath: /data/target}
{{- end }}
{{- end -}}
{{- define "toolkit.volumes" -}}
- name: source
  persistentVolumeClaim: {claimName: {{ .Values.source.claimName }}, readOnly: true}
- name: config
  configMap: {name: {{ .Release.Name }}-config}
- name: captures
  persistentVolumeClaim: {claimName: {{ .Release.Name }}-captures}
{{- if .Values.target.claimName }}
- name: target
  persistentVolumeClaim: {claimName: {{ .Values.target.claimName }}}
{{- end }}
{{- end -}}
