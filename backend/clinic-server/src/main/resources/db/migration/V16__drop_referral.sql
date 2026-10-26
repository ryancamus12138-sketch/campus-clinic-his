-- 转诊单（referral）在 V1 中建表后一直没有业务使用：转诊属于医疗业务，不在收费系统范围内（见 docs/design.md 第 2 章）
DROP TABLE referral;
