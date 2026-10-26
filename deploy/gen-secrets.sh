#!/usr/bin/env bash
# 生成生产环境密钥，写入 deploy/secrets/（已在 .gitignore 中）。只输出文件名，不输出密钥内容。
#
# - 已存在且非空的文件保留不动：数据密钥、索引密钥一旦投入使用就不能更换，丢失后已加密的数据无法解密；
# - 已存在但为空的文件视为损坏，停止执行，请从离线备份恢复，避免悄悄生成一把新密钥；
# - 目录权限 700，只有部署用户可进入。文件权限 644，是因为 MySQL、后端在容器内以非 root 用户运行，
#   Docker Compose 以只读方式把文件挂进容器，需要容器内的用户能读。
# 各文件由哪些服务读取、如何备份，见 docs/deploy.md 的“密钥”。
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p secrets
chmod 700 secrets

gen() { # 文件名 长度 格式(b64: 随机字节数 | alnum: 字符数)
  local file="secrets/$1" tmp value
  if [ -e "$file" ] && [ ! -s "$file" ]; then
    echo "错误：$file 存在但为空。请从离线备份恢复后再运行；确认从未使用过的，删除该文件后重新运行。" >&2
    exit 1
  fi
  if [ -s "$file" ]; then
    echo "保留已有 $file"
    return
  fi
  if [ "$3" = b64 ]; then
    value=$(head -c "$2" /dev/urandom | base64 | tr -d '\n')
  else
    # 数据库、Redis 密码只用字母数字，避免在 SQL 和命令行里被转义。
    # 先读有限长度的随机字节再过滤，不用 “tr < /dev/urandom | head”：在 pipefail 下 tr 会被 SIGPIPE 杀掉
    value=$(head -c 4096 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')
    value="${value:0:$2}"
    if [ "${#value}" -ne "$2" ]; then
      echo "错误：生成 $file 时随机数不足" >&2
      exit 1
    fi
  fi
  # 先写临时文件再改名：中途失败不会留下半个文件
  tmp=$(mktemp "secrets/.$1.XXXXXX")
  printf '%s' "$value" > "$tmp"
  mv "$tmp" "$file"
  echo "生成 $file"
}

gen db_root_password 32 alnum
gen db_app_password 32 alnum
gen db_migrator_password 32 alnum
gen redis_password 32 alnum
gen jwt_secret 48 b64
gen crypto_key_1 16 b64        # 证件号、手机号加密用的 SM4 数据密钥（128 位）
gen crypto_index_key 32 b64    # 检索用的 HMAC-SM3 盲索引密钥
gen admin_initial_password 16 alnum

chmod 644 secrets/*
echo "完成。请立即把 secrets/ 离线备份到安全位置。"
echo "首次登录管理员账号 admin 的初始口令在 secrets/admin_initial_password 中，登录后必须修改。"
