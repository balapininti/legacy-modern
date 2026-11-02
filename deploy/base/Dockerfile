# Runtime for the toolkit jar: a JRE, git for commit references, and Node with TypeScript for the Angular extraction.
# Everything here is under a permissive license except the OS packages the JRE image ships.
FROM eclipse-temurin:21-jre-noble
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git nodejs ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json /opt/ts/
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends npm && cd /opt/ts && npm ci --omit=dev --no-audit --no-fund && apt-get purge -y -qq npm && apt-get autoremove -y -qq && rm -rf /var/lib/apt/lists/* /root/.npm
RUN git config --system --add safe.directory '*'
