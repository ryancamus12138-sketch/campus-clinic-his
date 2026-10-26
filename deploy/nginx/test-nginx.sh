#!/usr/bin/env bash
# Nginx 镜像验证：构建与生产相同的 web 镜像，用一个回显请求的桩后端代替 Spring Boot，
# 分别以 HTTP（开发编排）、HTTPS（生产编排，自签名证书）、缺证书、证书签发引导四种方式启动并检查：
#   静态资源与前端路由刷新、/api/ 转发及转发头、安全响应头、请求体上限、管理端点限制、
#   80 跳转 443、HSTS、TLS 版本、ACME 验证目录、缺证书拒绝启动、证书更换后重新加载。
# 只验证 Nginx 这一层；完整系统（真实后端、数据库）的 HTTPS 部署由 Security 工作流的 production 任务验证。
#
# 运行：在仓库根目录执行 deploy/nginx/test-nginx.sh（需要 docker、curl、openssl）
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE=clinic-web:test
NET=clinic-nginx-test
WORK=$(mktemp -d)
P=clinic-nginx-test
fails=0

cleanup() {
  docker rm -f "$P-backend" "$P-http" "$P-https" "$P-nocert" "$P-acme" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

results=()
ok() { echo "ok   $1"; results+=("ok   $1"); }
bad() {
  echo "FAIL $1"
  results+=("FAIL $1")
  fails=$((fails + 1))
  # 在 GitHub Actions 中写成注释，提交页面上直接可见
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error title=Nginx check::$1"; fi
}
check() { # 描述 命令...
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi
}
wait_http() { # URL [curl 参数]
  for _ in $(seq 1 30); do
    curl -ks -o /dev/null "$@" && return 0
    sleep 1
  done
  return 1
}

echo "== 构建镜像"
docker build -q -f deploy/nginx/Dockerfile -t "$IMAGE" . >/dev/null
docker network create "$NET" >/dev/null

# 桩后端：容器名 backend，与编排中的服务名相同；返回收到的路径和转发头
cat > "$WORK/stub.py" <<'PY'
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def handle_one(self):
        n = int(self.headers.get('Content-Length') or 0)
        self.rfile.read(n)
        body = json.dumps({'path': self.path, 'proto': self.headers.get('X-Forwarded-Proto'),
                           'trace': self.headers.get('X-Trace-Id'), 'host': self.headers.get('Host')}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_GET = do_POST = handle_one
    def log_message(self, *a):
        pass
http.server.ThreadingHTTPServer(('', 8080), H).serve_forever()
PY
docker run -d --name "$P-backend" --network "$NET" --network-alias backend \
  -v "$WORK/stub.py:/stub.py:ro" python:3.13-alpine python /stub.py >/dev/null

# ------------------------------------------------------------------ HTTP（开发编排）
echo "== HTTP（CLINIC_TLS=off）"
docker run -d --name "$P-http" --network "$NET" -e CLINIC_TLS=off -p 18080:80 "$IMAGE" >/dev/null
wait_http http://127.0.0.1:18080/ || bad "HTTP 入口启动"
B=http://127.0.0.1:18080
# 桩后端（首次运行要拉取 Python 镜像）就绪后再检查转发
for _ in $(seq 1 60); do
  curl -fs -o /dev/null $B/api/ping && break
  sleep 1
done
check "nginx -t" docker exec "$P-http" nginx -t
check "首页返回管理端页面" sh -c "curl -fs $B/ | grep -q 'id=\"app\"'"
check "前端路由刷新（/charge/settle）返回 index.html" sh -c "curl -fs $B/charge/settle | grep -q 'id=\"app\"'"
check "index.html 不缓存" sh -c "curl -fsI $B/charge/settle | grep -qi '^cache-control: no-cache'"
asset=$(curl -fs $B/ | grep -o '/assets/[^"]*\.js' | head -n 1 || true)
check "静态资源 $asset 可访问且长期缓存" sh -c "curl -fsI $B$asset | grep -qi 'cache-control: public, immutable'"
check "静态资源带安全响应头" sh -c "curl -fsI $B$asset | grep -qi '^x-content-type-options: nosniff'"
check "/api/ 转发到后端，路径不变" sh -c "curl -fs $B/api/auth/options | grep -q '\"path\": \"/api/auth/options\"'"
check "转发头 X-Forwarded-Proto=http、X-Trace-Id" sh -c "curl -fs $B/api/x | grep -q '\"proto\": \"http\", \"trace\": \"[0-9a-f]\{32\}\"'"
check "安全响应头（X-Frame-Options、CSP）" sh -c "curl -fsI $B/ | grep -qi '^x-frame-options: DENY' && curl -fsI $B/ | grep -qi '^content-security-policy:'"
check "HTTP 下不发送 HSTS" sh -c "! curl -fsI $B/ | grep -qi '^strict-transport-security'"
check "请求体超过 1 MB 返回 413" sh -c "head -c 2000000 /dev/zero | curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary @- $B/api/x | grep -q 413"
check "指标端点不经 Nginx 对外提供（/actuator/prometheus 404）" sh -c "curl -s -o /dev/null -w '%{http_code}' $B/actuator/prometheus | grep -q 404"
check "Nginx 存活检查 /healthz 返回 ok" sh -c "curl -fs $B/healthz | grep -qx ok"
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-http")" = healthy ] && break
  sleep 2
done
check "容器健康检查为 healthy" test "$(docker inspect -f '{{.State.Health.Status}}' "$P-http")" = healthy

# ------------------------------------------------------------------ 生产：缺证书
echo "== 生产（CLINIC_TLS=required）缺少证书"
mkdir -p "$WORK/empty-certs"
docker run -d --name "$P-nocert" --network "$NET" -e CLINIC_TLS=required \
  -v "$WORK/empty-certs:/etc/nginx/certs:ro" "$IMAGE" >/dev/null || true
for _ in $(seq 1 15); do
  [ "$(docker inspect -f '{{.State.Running}}' "$P-nocert")" = false ] && break
  sleep 1
done
state=$(docker inspect -f '{{.State.Running}} {{.State.ExitCode}}' "$P-nocert")
check "缺证书时容器退出，不以 HTTP 启动（状态：$state）" test "${state%% *}" = false -a "${state##* }" != 0
check "日志说明拒绝启动的原因" sh -c "docker logs $P-nocert 2>&1 | grep -q '拒绝启动'"

# ------------------------------------------------------------------ 生产：HTTPS
echo "== 生产（CLINIC_TLS=required）HTTPS"
mkdir -p "$WORK/certs" "$WORK/acme/.well-known/acme-challenge"
echo "token-content" > "$WORK/acme/.well-known/acme-challenge/test-token"
chmod -R a+rX "$WORK/acme"
gen_cert() {
  openssl req -x509 -newkey rsa:2048 -nodes -days "$1" -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -keyout "$WORK/certs/privkey.pem" -out "$WORK/certs/fullchain.pem" 2>/dev/null
  chmod 644 "$WORK/certs/"*.pem
}
gen_cert 30
docker run -d --name "$P-https" --network "$NET" -e CLINIC_TLS=required -p 18081:80 -p 18443:443 \
  -v "$WORK/certs:/etc/nginx/certs:ro" -v "$WORK/acme:/var/www/acme:ro" "$IMAGE" >/dev/null
wait_http https://127.0.0.1:18443/ || bad "HTTPS 入口启动"
S=https://127.0.0.1:18443
check "nginx -t" docker exec "$P-https" nginx -t
check "HTTP 跳转到 HTTPS（301）" sh -c "curl -s -o /dev/null -w '%{http_code} %{redirect_url}' http://127.0.0.1:18081/charge/settle | grep -q '^301 https://127.0.0.1/charge/settle'"
check "80 端口提供 ACME 验证文件" sh -c "curl -fs http://127.0.0.1:18081/.well-known/acme-challenge/test-token | grep -q token-content"
check "HTTPS 首页" sh -c "curl -kfs $S/ | grep -q 'id=\"app\"'"
check "HTTPS 前端路由刷新" sh -c "curl -kfs $S/finance/shifts | grep -q 'id=\"app\"'"
check "HTTPS 带 HSTS（两年）" sh -c "curl -kfsI $S/ | grep -qi '^strict-transport-security: max-age=63072000'"
check "HTTPS 转发头 X-Forwarded-Proto=https" sh -c "curl -kfs $S/api/x | grep -q '\"proto\": \"https\"'"
check "接受 TLS 1.2" sh -c "echo | openssl s_client -connect 127.0.0.1:18443 -tls1_2 2>/dev/null | grep -Eq 'New, TLSv1.2|Protocol *: TLSv1.2'"
check "接受 TLS 1.3" sh -c "echo | openssl s_client -connect 127.0.0.1:18443 -tls1_3 2>/dev/null | grep -Eq 'New, TLSv1.3|Protocol *: TLSv1.3'"
check "拒绝 TLS 1.1" sh -c "! echo | openssl s_client -connect 127.0.0.1:18443 -tls1_1 2>/dev/null | grep -q 'Protocol *: TLSv1.1'"

# 证书更换后：检查配置、重新加载，正在使用的证书随之更新（与 ./clinic.sh reload-nginx 相同的步骤）
before=$(echo | openssl s_client -connect 127.0.0.1:18443 2>/dev/null | openssl x509 -noout -serial || true)
gen_cert 90
docker exec "$P-https" nginx -t >/dev/null 2>&1 && docker exec "$P-https" nginx -s reload >/dev/null 2>&1
sleep 2
after=$(echo | openssl s_client -connect 127.0.0.1:18443 2>/dev/null | openssl x509 -noout -serial || true)
check "更换证书并 reload 后使用新证书" test -n "$after" -a "$before" != "$after"

# ------------------------------------------------------------------ 首次签发证书的引导
echo "== 证书签发引导（CLINIC_TLS=acme-bootstrap，不连接后端）"
docker run -d --name "$P-acme" -e CLINIC_TLS=acme-bootstrap -p 18082:80 \
  -v "$WORK/acme:/var/www/acme:ro" "$IMAGE" >/dev/null
wait_http http://127.0.0.1:18082/.well-known/acme-challenge/test-token || bad "引导入口启动"
check "引导模式提供 ACME 验证文件" sh -c "curl -fs http://127.0.0.1:18082/.well-known/acme-challenge/test-token | grep -q token-content"
check "引导模式不提供管理端页面（503）" sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18082/ | grep -q 503"
check "引导模式不转发接口（503）" sh -c "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18082/api/auth/options | grep -q 503"

echo
if [ -n "${GITHUB_ACTIONS:-}" ]; then
  summary=$(printf '%s\n' "${results[@]}")
  echo "::notice title=Nginx checks::${summary//$'\n'/%0A}"
fi
if [ "$fails" -gt 0 ]; then
  echo "Nginx 验证：$fails 项未通过"
  for c in http https nocert acme; do
    logs=$(docker logs "$P-$c" 2>&1 | tail -n 15 || true)
    echo "--- $c"; echo "$logs"
    if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning title=nginx logs $c::${logs//$'\n'/%0A}"; fi
  done
  exit 1
fi
echo "Nginx 验证全部通过"
