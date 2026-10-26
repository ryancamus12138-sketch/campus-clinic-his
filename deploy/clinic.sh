#!/usr/bin/env bash
# 生产环境运维入口：把多份编排文件的组合固定下来，避免手工拼命令出错。
#
#   ./clinic.sh up                    启动或更新全部服务（证书、密钥不全时拒绝启动）
#   ./clinic.sh status                查看各服务状态
#   ./clinic.sh logs [服务]           查看日志（默认后端）
#   ./clinic.sh upgrade               升级：拉取代码、构建、重启（数据库迁移自动执行）
#   ./clinic.sh cert-check            检查 certs/ 下的证书：与私钥是否匹配、有效期
#   ./clinic.sh reload-nginx          证书更新后：检查证书和 Nginx 配置，重新加载，显示正在使用的证书
#   ./clinic.sh cert-bootstrap        首次签发证书：80 端口只提供 ACME 验证文件（不提供系统页面）
#   ./clinic.sh cert-bootstrap-stop   结束首次签发引导
#   ./clinic.sh down                  停止全部服务（数据卷保留）
#
# 升级前请先确认当天的数据库备份已完成（见 docs/deploy.md 的“备份”）。
set -euo pipefail
cd "$(dirname "$0")"

FILES=(-f docker-compose.yml -f docker-compose.prod.yml)
compose() { docker compose "${FILES[@]}" "$@"; }
BOOTSTRAP=clinic-cert-bootstrap
SECRETS=(db_root_password db_app_password db_migrator_password redis_password jwt_secret crypto_key_1
  crypto_index_key admin_initial_password)

env_value() { # 从 .env 读取一个配置项，没有时用默认值
  local v
  v=$(grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true)
  printf '%s' "${v:-$2}"
}

require_secrets() {
  local missing=()
  for s in "${SECRETS[@]}"; do
    [ -s "secrets/$s" ] || missing+=("$s")
  done
  if [ ${#missing[@]} -gt 0 ]; then
    echo "缺少密钥文件：${missing[*]}。首次部署请运行 ./gen-secrets.sh；已部署过的请从离线备份恢复 secrets/" >&2
    exit 1
  fi
}

# 证书检查：文件存在且非空；有 openssl 时再检查与私钥是否匹配、是否已过期
require_certs() {
  if [ ! -s certs/fullchain.pem ] || [ ! -s certs/privkey.pem ]; then
    echo "certs/ 下缺少 fullchain.pem 或 privkey.pem。生产环境只提供 HTTPS，不会以 HTTP 启动。" >&2
    echo "已有证书：复制到 certs/；需要首次签发：./clinic.sh cert-bootstrap（见 docs/deploy.md）" >&2
    exit 1
  fi
  if ! command -v openssl >/dev/null; then
    echo "提示：宿主机没有 openssl，跳过证书与私钥匹配、有效期检查"
    return
  fi
  local cert_key key_key
  cert_key=$(openssl x509 -in certs/fullchain.pem -noout -pubkey 2>/dev/null | openssl sha256) \
    || { echo "certs/fullchain.pem 不是有效的 PEM 证书" >&2; exit 1; }
  key_key=$(openssl pkey -in certs/privkey.pem -pubout 2>/dev/null | openssl sha256) \
    || { echo "certs/privkey.pem 不是有效的私钥（或带口令）" >&2; exit 1; }
  [ "$cert_key" = "$key_key" ] || { echo "证书与私钥不匹配" >&2; exit 1; }
  openssl x509 -in certs/fullchain.pem -noout -checkend 0 >/dev/null \
    || { echo "证书已过期：$(openssl x509 -in certs/fullchain.pem -noout -enddate)" >&2; exit 1; }
  if ! openssl x509 -in certs/fullchain.pem -noout -checkend $((30 * 86400)) >/dev/null; then
    echo "警告：证书 30 天内到期（$(openssl x509 -in certs/fullchain.pem -noout -enddate)），请尽快续期"
  fi
  echo "证书：$(openssl x509 -in certs/fullchain.pem -noout -subject)；$(openssl x509 -in certs/fullchain.pem -noout -enddate)"
}

case "${1:-}" in
  up)
    require_secrets
    require_certs
    if docker ps -q --filter "name=^${BOOTSTRAP}$" | grep -q .; then
      echo "证书签发引导仍在运行，请先执行 ./clinic.sh cert-bootstrap-stop" >&2
      exit 1
    fi
    compose up -d --build
    compose ps
    ;;
  status)
    compose ps
    ;;
  logs)
    compose logs --tail 200 -f "${2:-backend}"
    ;;
  upgrade)
    require_secrets
    require_certs
    echo "1/3 拉取代码（升级前请确认当天的数据库备份已完成）"
    git -C .. pull --ff-only
    echo "2/3 构建镜像"
    compose build
    echo "3/3 重启服务（数据库迁移在后端启动时自动执行）"
    compose up -d
    compose ps
    ;;
  cert-check)
    require_certs
    ;;
  reload-nginx)
    require_certs
    compose exec web nginx -t
    compose exec web nginx -s reload
    if command -v openssl >/dev/null; then
      sleep 1
      port=$(env_value HTTPS_PORT 443)
      echo "正在使用的证书："
      openssl s_client -connect "127.0.0.1:${port}" </dev/null 2>/dev/null | openssl x509 -noout -subject -enddate
    fi
    ;;
  cert-bootstrap)
    if docker ps -q --filter "name=^${BOOTSTRAP}$" | grep -q .; then
      echo "引导已在运行"
      exit 0
    fi
    if [ -n "$(compose ps -q web 2>/dev/null)" ]; then
      echo "Nginx 正在运行（80 端口被占用）。已运行的系统续期证书不需要引导，直接用 80 端口的验证目录即可。" >&2
      exit 1
    fi
    mkdir -p acme certs
    compose build web
    port=$(env_value HTTP_PORT 80)
    compose run -d --rm --no-deps --name "$BOOTSTRAP" -p "${port}:80" -e CLINIC_TLS=acme-bootstrap web >/dev/null
    echo "引导已启动：80 端口只提供 $(pwd)/acme 下的验证文件，其余请求返回 503。"
    echo "下一步：在宿主机用证书工具签发，例如"
    echo "  certbot certonly --webroot -w $(pwd)/acme -d <域名>"
    echo "把 fullchain.pem、privkey.pem 复制到 $(pwd)/certs/ 后执行："
    echo "  ./clinic.sh cert-bootstrap-stop && ./clinic.sh up"
    ;;
  cert-bootstrap-stop)
    docker rm -f "$BOOTSTRAP" >/dev/null 2>&1 || true
    echo "引导已结束"
    ;;
  down)
    compose down
    ;;
  *)
    sed -n '2,14p' "$0"
    exit 1
    ;;
esac
