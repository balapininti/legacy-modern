#!/usr/bin/env bash
# The release tarball: images, Compose bundle, Helm chart, runbook and SBOM. Nothing is fetched at install or at runtime.
# Models are optional and large: pass their names to include them, for example: scripts/build-airgap-bundle.sh qwen2.5 nomic-embed-text
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"; source scripts/env.sh
v=0.1.0-SNAPSHOT; out="$root/target/toolkit-$v-airgap"; rm -rf "$out"; mkdir -p "$out/images" "$out/models"
images="modtoolkit/toolkit:$v modtoolkit/toolkit-ui:$v pgvector/pgvector:pg16"
[ $# -gt 0 ] && images="$images ollama/ollama:0.12.3"
docker pull -q pgvector/pgvector:pg16 >/dev/null || true
for i in $images; do docker save "$i" | gzip -1 > "$out/images/$(echo "$i" | tr '/:' '__').tar.gz"; done
cp -R deploy/compose "$out/compose"; rm -rf "$out/compose/.env" "$out/compose/tls"
cp -R deploy/helm "$out/helm"; cp deploy/INSTALL.md "$out/"
mvn -B -q cyclonedx:makeAggregateBom -DskipTests >/dev/null; cp target/bom.json "$out/sbom.json"
for m in "$@"; do
  cp -R "$HOME/.ollama/models" "$out/models/" 2>/dev/null || true
  echo "$m" >> "$out/models/names.txt"
done
cat > "$out/load-images.sh" <<'L'
#!/usr/bin/env sh
cd "$(dirname "$0")/images" && for f in *.tar.gz; do echo "loading $f"; gunzip -c "$f" | docker load -q; done
L
chmod +x "$out/load-images.sh"
tar -C "$root/target" -czf "$out.tar.gz" "$(basename "$out")"
ls -la "$out.tar.gz" | awk '{print $5, $9}'
