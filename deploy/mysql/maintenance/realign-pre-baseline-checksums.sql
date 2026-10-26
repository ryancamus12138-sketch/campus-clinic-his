-- =====================================================================
-- 一次性运维步骤：迁移基线之前建立的开发数据库，对齐 6 个迁移的 Flyway 校验值
--
-- 背景
--   系统首次部署之前（2026-10-26），V2、V6、V13、V20、V1001、V1002 被改写过一次，
--   只涉及示例数据和注释。执行到 V20 之后，改写前后得到的数据相同；
--   只是 Flyway 记录的校验值不同，用改写前的脚本建过的数据库启动时会报 checksum mismatch。
--   在此之前没有任何生产部署，受影响的只可能是开发或演示环境的数据库。
--
-- 适用条件（全部满足才执行）
--   1. 后端启动失败，日志中的 checksum mismatch 只涉及上述 6 个版本；
--   2. 该数据库是在基线提交之前建立的；
--   3. 已备份数据库。
--   其他任何 checksum mismatch 都不适用：应恢复被修改的迁移文件（以 migration-lock.txt 为准），改动写成新版本。
--
-- 做法：只修改“版本号与改写前校验值都完全匹配”的行，其他行不动；可以重复执行。应用启动时不会自动执行本脚本。
--   docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" clinic' < mysql/maintenance/realign-pre-baseline-checksums.sql
--
-- 移除条件：发布第一个正式版本（打版本标签）时删除本脚本及其测试 MigrationBaselineIntegrationTest。
-- =====================================================================

UPDATE flyway_schema_history SET checksum = 384915636   WHERE version = '2'    AND checksum = 56246269   AND success = 1;
UPDATE flyway_schema_history SET checksum = 2094431777  WHERE version = '6'    AND checksum = -98093218  AND success = 1;
UPDATE flyway_schema_history SET checksum = 126605299   WHERE version = '13'   AND checksum = -374022545 AND success = 1;
UPDATE flyway_schema_history SET checksum = -1819597839 WHERE version = '20'   AND checksum = 667515292  AND success = 1;
UPDATE flyway_schema_history SET checksum = 1626589457  WHERE version = '1001' AND checksum = 1583789921 AND success = 1;
UPDATE flyway_schema_history SET checksum = -214237903  WHERE version = '1002' AND checksum = -688524770 AND success = 1;

SELECT version, description, checksum FROM flyway_schema_history
WHERE version IN ('2', '6', '13', '20', '1001', '1002') ORDER BY installed_rank;
