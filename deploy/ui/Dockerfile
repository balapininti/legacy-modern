# Built from the repository root: the Angular build, served by nginx, which also terminates TLS.
FROM node:22-alpine AS build
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY deploy/ui/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /ui/dist/ui/browser /usr/share/nginx/html
