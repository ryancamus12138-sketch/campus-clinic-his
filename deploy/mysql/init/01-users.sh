#!/bin/bash
# MySQL 首次初始化时执行（挂载到 /docker-entrypoint-initdb.d/，数据卷为空时才会运行）。
#
# 最小权限的两个账号：
#   clinic_migrator  只在应用启动时执行 Flyway 迁移，拥有两个库的全部表权限
#   clinic_app       应用运行时使用：业务库增删改查；审计库只能查询和插入，不能修改或删除审计日志
# root 只用于运维，不交给应用。
#
# 密码来自环境变量，或 *_FILE 指向的文件（Docker secrets）。
set -euo pipefail

read_secret() {
  local name="$1" file_var="${1}_FILE"
  if [ -n "${!file_var:-}" ]; then
    cat "${!file_var}"
  else
    printf '%s' "${!name:?$name 未设置}"
  fi
}

app_password="$(read_secret DB_APP_PASSWORD)"
migrator_password="$(read_secret DB_MIGRATOR_PASSWORD)"
for p in "$app_password" "$migrator_password"; do
  # 生成脚本只产出字母数字，这里拒绝会破坏 SQL 字符串的字符
  if [[ ! "$p" =~ ^[A-Za-z0-9_+/=.-]{12,}$ ]]; then
    echo "数据库密码至少 12 位，且只能包含字母、数字和 _+/=.-" >&2
    exit 1
  fi
done

root_password="${MYSQL_ROOT_PASSWORD:-}"
if [ -n "${MYSQL_ROOT_PASSWORD_FILE:-}" ]; then
  root_password="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"
fi

mysql --protocol=socket -uroot -p"$root_password" <<SQL
CREATE DATABASE IF NOT EXISTS clinic DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS clinic_audit DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'clinic_migrator'@'%' IDENTIFIED BY '${migrator_password}';
GRANT ALL PRIVILEGES ON clinic.* TO 'clinic_migrator'@'%';
GRANT ALL PRIVILEGES ON clinic_audit.* TO 'clinic_migrator'@'%';

CREATE USER IF NOT EXISTS 'clinic_app'@'%' IDENTIFIED BY '${app_password}';
GRANT SELECT, INSERT, UPDATE, DELETE ON clinic.* TO 'clinic_app'@'%';
GRANT SELECT, INSERT ON clinic_audit.* TO 'clinic_app'@'%';
FLUSH PRIVILEGES;
SQL

echo "clinic_migrator / clinic_app 已创建"
