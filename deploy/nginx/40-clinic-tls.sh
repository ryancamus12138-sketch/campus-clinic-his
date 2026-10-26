#!/bin/sh
# 官方镜像启动时执行 /docker-entrypoint.d/ 下的脚本；脚本失败时容器退出，Nginx 不会启动。
# 按 CLINIC_TLS 选择入口配置：
#   off             HTTP（开发与演示编排的默认值）
#   required        HTTPS：80 端口只做跳转和 ACME 验证，443 提供服务。证书缺失或为空时拒绝启动，
#                   不会退回明文 HTTP（生产编排固定使用此值）
#   acme-bootstrap  首次签发证书：80 端口只提供 /.well-known/acme-challenge/，其余返回 503
set -eu

mode="${CLINIC_TLS:-off}"
cert=/etc/nginx/certs/fullchain.pem
key=/etc/nginx/certs/privkey.pem

case "$mode" in
  off)
    cp /etc/nginx/clinic/nginx.conf /etc/nginx/conf.d/default.conf
    echo "clinic: CLINIC_TLS=off，以 HTTP 提供服务（仅用于开发与演示）"
    ;;
  required)
    if [ ! -s "$cert" ] || [ ! -s "$key" ]; then
      echo "clinic: CLINIC_TLS=required，但证书 $cert 或私钥 $key 不存在或为空，拒绝启动。" >&2
      echo "clinic: 放置证书后重新启动；首次签发见 docs/deploy.md 的“证书”一节。" >&2
      exit 1
    fi
    cp /etc/nginx/clinic/https.conf /etc/nginx/conf.d/default.conf
    echo "clinic: 启用 HTTPS（80 端口跳转到 443）"
    ;;
  acme-bootstrap)
    cp /etc/nginx/clinic/acme-bootstrap.conf /etc/nginx/conf.d/default.conf
    echo "clinic: 证书签发引导模式：80 端口只提供 ACME 验证文件，不提供系统页面和接口"
    ;;
  *)
    echo "clinic: 未知的 CLINIC_TLS=$mode（可选 off、required、acme-bootstrap）" >&2
    exit 1
    ;;
esac
