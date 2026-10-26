# 部署手册

本文说明如何在一台服务器上部署系统，以及证书、密钥、备份和常见问题的处理。系统结构见[设计文档](design.md)。

## 1 部署结构

| 服务 | 作用 | 对外端口 |
| --- | --- | --- |
| web（Nginx） | 管理端页面、HTTPS、限流，把 `/api/` 转发给后端 | 80（只跳转到 HTTPS 和提供证书验证文件）、443 |
| backend（Spring Boot） | 业务接口；启动时执行数据库迁移 | 无 |
| mysql（MySQL 8.4） | 业务库 `clinic`、日志库 `clinic_audit` | 无 |
| redis（Redis 8） | 登录令牌、登录失败计数、号源预扣 | 无 |

四个服务由 Docker Compose 运行在同一台服务器上，只有 Nginx 映射到宿主机。生产编排由两份文件组合而成（`docker-compose.yml` + `docker-compose.prod.yml`），通过 `deploy/clinic.sh` 使用，不要手工拼命令。

| 健康检查 | 说明 |
| --- | --- |
| 容器健康检查 | 后端检查 `/actuator/health`，Nginx 检查自身的 `/healthz`；`./clinic.sh status` 中显示 `healthy`。Nginx 在后端健康后才启动 |
| 外部监控 | `https://<域名>/actuator/health` 只允许内网地址访问；其他管理端点不经 Nginx 提供 |

## 2 服务器要求

| 项目 | 要求 |
| --- | --- |
| CPU / 内存 | 4 核 8 GB 起 |
| 磁盘 | SSD 100 GB 以上 |
| 软件 | Docker Engine 27 以上（含 Compose 插件）、git、openssl |
| 网络 | 对外只开放 80、443 端口 |

## 3 首次部署

### 3.1 获取代码和配置

```bash
git clone https://github.com/ryancamus12138-sketch/campus-clinic-his.git /opt/clinic
cd /opt/clinic/deploy
cp .env.example .env      # 修改机构名称 CLINIC_ORG_NAME；端口默认 80、443
```

`.env` 只放非机密配置。口令和密钥放在 `secrets/` 下，不出现在环境变量、镜像和仓库中。

### 3.2 密钥

```bash
./gen-secrets.sh
```

脚本只输出文件名，不输出内容。已存在的文件不会覆盖；存在但为空的文件视为损坏，脚本停止执行。

| 文件 | 读取的服务 | 用途 |
| --- | --- | --- |
| `db_root_password` | mysql | MySQL root 口令，只用于运维 |
| `db_app_password` | mysql（初始化时建账号）、backend | 应用账号 `clinic_app` |
| `db_migrator_password` | mysql（初始化时建账号）、backend | 迁移账号 `clinic_migrator`，只在启动迁移时使用 |
| `redis_password` | redis、backend | Redis 访问口令 |
| `jwt_secret` | backend | 登录令牌签名 |
| `crypto_key_1` | backend | 证件号、手机号加密的数据密钥 |
| `crypto_index_key` | backend | 证件号、手机号检索用的索引密钥 |
| `admin_initial_password` | backend（首次启动） | 管理员 `admin` 的初始口令，首次登录后必须修改 |

保存与备份：

- `secrets/` 目录权限为 700，只有部署用户可进入；文件为 644，是为了让容器内的非 root 用户能读取。
- 生成后立即把整个 `secrets/` 复制到离线介质（如加密 U 盘）并与服务器分开保管。不要提交到仓库，不要通过聊天工具或邮件传送。
- `crypto_key_1` 和 `crypto_index_key` 丢失后，库中已加密的证件号和手机号将无法解密，也无法检索；这两个文件不能重新生成。
- 数据库口令在 MySQL 首次初始化时写入。初始化之后只修改文件不会改变数据库中的口令。

### 3.3 证书

生产编排只提供 HTTPS。`certs/fullchain.pem` 或 `certs/privkey.pem` 缺失时，`./clinic.sh up` 拒绝执行，Nginx 容器也会拒绝启动，不会退回明文 HTTP。

**已有证书**（学校统一申请或购买）：把证书链和私钥分别保存为 `certs/fullchain.pem`、`certs/privkey.pem`，执行 `./clinic.sh cert-check`，确认证书与私钥匹配、未过期。

**首次用 ACME（如 Let's Encrypt）签发**：需要域名已解析到本服务器、80 端口可从公网访问。

```bash
./clinic.sh cert-bootstrap                                  # 80 端口只提供 acme/ 下的验证文件，其余请求返回 503
certbot certonly --webroot -w /opt/clinic/deploy/acme -d <域名>
cp /etc/letsencrypt/live/<域名>/fullchain.pem certs/fullchain.pem
cp /etc/letsencrypt/live/<域名>/privkey.pem  certs/privkey.pem
./clinic.sh cert-bootstrap-stop
./clinic.sh up
```

引导期间系统不提供页面和接口。certbot 在宿主机上运行，不包含在本项目中。

**续期**：本项目不包含自动续期，续期由部署环境负责（如宿主机上 certbot 的定时任务，或学校的证书管理流程）。系统运行时，80 端口仍提供 `acme/` 下的验证文件，可直接用 `--webroot` 续期。证书文件更新后执行：

```bash
./clinic.sh reload-nginx
```

该命令依次检查证书与私钥是否匹配、是否过期，执行 `nginx -t` 检查配置，重新加载 Nginx，最后显示 443 端口正在使用的证书及到期时间。使用 certbot 时，可以把复制证书和这条命令写进 `--deploy-hook`。证书 30 天内到期时，`cert-check` 和 `reload-nginx` 会给出警告。

### 3.4 启动

```bash
./clinic.sh up        # 检查密钥和证书，构建并启动
./clinic.sh status    # 查看各服务状态
```

首次启动时，MySQL 初始化脚本创建业务库、日志库和最小权限账号；后端用 Flyway 迁移脚本建表；系统用 `secrets/admin_initial_password` 中的口令创建管理员 `admin`。

### 3.5 初始配置

1. 用 `admin` 登录并修改口令。
2. 在“用户管理”中为员工开通账号并分配角色。系统生成临时口令，员工首次登录时须修改。审计管理员须由系统管理员以外的人担任。
3. 在“校区与收费点”中修改校区名称和地址，并为每个收费窗口选择开放的收款方式。
4. 在“收费项目”和“药品目录”中核对价格、医保编码和是否可报销。
5. 按本校参保类型设置报销比例：在 `deploy/config/application.yml` 中写入 `clinic.charge.fee-policies`（格式与 `backend/clinic-server/src/main/resources/application.yml` 中的示例相同，写入后整组替换示例），然后执行 `./clinic.sh up`。该比例只用于划价估算，实际统筹金额以医保结算结果为准。

   ```yaml
   clinic:
     charge:
       fee-policies:
         - code: STU
           name: 大学生医保
           insurance-types: [1]
           ratio: 0.80
   ```

## 4 备份

本项目不包含备份程序，备份由部署环境负责。需要备份的内容：

| 内容 | 做法 |
| --- | --- |
| 业务库 `clinic`、日志库 `clinic_audit` | 每日全量备份，例如 `docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T mysql sh -c 'mysqldump -uroot -p"$(cat /run/secrets/db_root_password)" --single-transaction --routines --databases clinic clinic_audit' > clinic-$(date +%F).sql`。生产 MySQL 保留 7 天二进制日志，配合全量备份可恢复到任意时间点 |
| `secrets/` | 首次生成后离线备份一次；变更后重新备份 |
| `certs/`、`.env` | 变更后备份 |

备份文件含患者信息，应加密保存在服务器以外的位置，并定期做恢复演练。Redis 中的数据可以丢失，不需要备份。

## 5 日常维护

| 操作 | 命令或位置 |
| --- | --- |
| 查看后端日志 | `./clinic.sh logs backend`；页面报错时提示的链路 ID 可用于在日志中检索 |
| 查看操作记录 | 审计管理员在管理端“操作日志”中查询 |
| 升级 | `./clinic.sh upgrade`（拉取代码、构建、重启，数据库迁移自动执行）；升级前确认当天备份已完成 |
| 证书 | `./clinic.sh cert-check` 查看有效期；更新后 `./clinic.sh reload-nginx` |
| 分区 | 资金流水等大表按年度分区，现有分区到 2026 年底。每年年底前用新增迁移补充下一年度的分区（见设计文档“迁移”） |

财务每天需要处理的事项：

- “收款异常”中待处理的记录；
- “退费”中待审核的退费，以及待收费员确认的现金和银行卡退款；
- 待复核的交班单；
- “电子票据”中待人工开具的票据。

## 6 常见问题

| 现象 | 处理 |
| --- | --- |
| 系统打不开 | `./clinic.sh status` 查看服务是否 `healthy`，`./clinic.sh logs backend` 查看错误；提示加密配置缺失时，从离线备份恢复 `secrets/` |
| `./clinic.sh up` 提示缺少证书，或 web 容器反复退出 | 证书未放置或为空。按 3.3 放置证书，或用 `cert-bootstrap` 首次签发 |
| 收费时提示“已转为收款异常” | 收费中途失败，有已收的现金或 POS 交易系统无法确认已退回。收费员当面退还现金、在 POS 机上撤销交易；财务在“收款异常”中逐笔核实（现金写明说明，银行卡填写撤销参考号），全部核实后登记处理结果，收费单解除锁定，患者重新缴费 |
| 退费一直显示“退款中” | 现金、银行卡退款需收费员在“退费”中点击“确认已退还”；有医保结算的，需先在医保结算终端撤销并重新结算，再点击“登记医保重结” |
| 交班提示须填写说明 | 实点现金或 POS 结算单金额与系统不一致，填写差额原因后交班，由财务复核 |
| 登录提示“该账号不具备所选身份” | 在登录页切换为与账号岗位一致的身份 |
| 后端启动失败，日志提示迁移校验不一致（checksum mismatch） | 已执行过的迁移脚本被修改过。恢复该脚本的原内容（以 `backend/clinic-server/src/test/resources/migration-lock.txt` 中登记的 SHA-256 为准），需要的改动写成新版本的迁移（如 `V23__adjust_xxx.sql`）后再升级。不能删除数据库卷来规避，也不要手工修改 `flyway_schema_history`。唯一的例外见下一行 |
| 基线之前建立的开发数据库报 checksum mismatch，且只涉及 V2、V6、V13、V20、V1001、V1002 | 这 6 个脚本在首次部署前改写过一次（见设计文档“迁移”），数据结果相同。备份后执行一次性运维脚本 `deploy/mysql/maintenance/realign-pre-baseline-checksums.sql`（用法写在脚本开头），它只修改与改写前校验值完全匹配的行。应用启动时不会自动执行该脚本 |

## 7 开发与演示环境

开发编排（在 `deploy` 目录执行 `docker compose up -d --build`，`PROFILE=dev`）只提供 HTTP（http://localhost），并写入开发环境演示数据：

- Flyway 执行 `backend/clinic-server/src/main/resources/db/seed-dev` 中的演示数据脚本，写入演示账号、收费项目、药品和两位演示患者；
- 后端启动时由开发环境初始化 `DevScheduleInitializer` 补齐未来 7 天的演示号源（只在 `dev` Profile 下执行）。

这些都是合成数据，测试和生产（`prod` Profile）都不加载。

| 角色 | 用户名 | 口令 |
| --- | --- | --- |
| 系统管理员 | admin | Admin@2026 |
| 收费员 | cashier01 | Clinic@2026 |
| 财务 | finance01 | Clinic@2026 |
| 医生 | doctor01 | Clinic@2026 |
| 药师 | pharm01 | Clinic@2026 |
| 审计管理员 | auditor01 | Clinic@2026 |

演示患者：学号 `D20210001`，工号 `T19990001`。以上账号只用于开发和演示，不得用于生产环境。需要清空开发环境的演示数据时，可以执行 `docker compose down -v` 后重新启动；这只适用于开发环境，不能用来处理迁移校验问题。

`deploy/nginx/test-nginx.sh` 用桩后端验证 Nginx 镜像的四种启动方式（HTTP、HTTPS、缺证书、证书签发引导），CI 每次提交都会运行。
