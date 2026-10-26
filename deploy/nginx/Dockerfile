# syntax=docker/dockerfile:1
# 构建上下文为仓库根目录：构建管理端，由 Nginx 托管

FROM node:24-alpine AS web
# 国内网络可传入 --build-arg NPM_REGISTRY=https://registry.npmmirror.com
ARG NPM_REGISTRY=""
RUN if [ -n "$NPM_REGISTRY" ]; then npm config set registry "$NPM_REGISTRY"; fi
WORKDIR /build/admin-web
COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY admin-web/ ./
RUN npm run build

FROM nginx:1.30-alpine
RUN apk upgrade --no-cache
# 入口配置由启动脚本按 CLINIC_TLS 复制为 conf.d/default.conf；镜像中不预置任何入口，
# 启动脚本失败时不会有现成的 HTTP 配置被启用
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx/nginx.conf deploy/nginx/https.conf deploy/nginx/acme-bootstrap.conf /etc/nginx/clinic/
COPY deploy/nginx/security-headers.conf deploy/nginx/clinic-locations.conf /etc/nginx/snippets/
COPY --chmod=755 deploy/nginx/40-clinic-tls.sh /docker-entrypoint.d/40-clinic-tls.sh
COPY --from=web /build/admin-web/dist /usr/share/nginx/html/admin
EXPOSE 80 443
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
