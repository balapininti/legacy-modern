#!/usr/bin/env sh
# A self-signed certificate for first setup. Replace tls/tls.crt and tls/tls.key with one from the client's CA.
cd "$(dirname "$0")" || exit 1
mkdir -p tls
[ -f tls/tls.key ] && { echo "tls/tls.key exists, leaving it"; exit 0; }
name="${1:-localhost}"
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout tls/tls.key -out tls/tls.crt -subj "/CN=$name" -addext "subjectAltName=DNS:$name,DNS:localhost,IP:127.0.0.1" 2>/dev/null
# The nginx container runs as an unprivileged user, so it needs to read the key through the mount.
chmod 644 tls/tls.key tls/tls.crt
echo "tls/tls.key is world-readable so the container can read it: keep this directory private"
echo "wrote tls/tls.crt for $name"
