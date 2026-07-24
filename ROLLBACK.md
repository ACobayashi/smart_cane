# ROLLBACK

## 回滚代码
1. 使用 Git：
   ```bash
   git checkout -- backend firmware frontend docs README.md API_CONTRACT.md CHANGELOG_FULL_CHAIN.md DATABASE_MIGRATION.md TEST_REPORT.md tests
   ```
2. 或者用交付前备份覆盖对应文件。

## 回滚数据库
本次新增表不影响旧表。若需要删除新增结构：
```sql
DROP TABLE IF EXISTS road_risk_scores;
DROP TABLE IF EXISTS road_traversals;
DROP TABLE IF EXISTS road_risk_observations;
DROP TABLE IF EXISTS road_segments;
DROP TABLE IF EXISTS navigation_sessions;
```

## 回滚部署
- 固件：重新烧录上一版 `firmware/smartcane_arduino/`。
- 后端：回退 `backend/main.py`、`backend/deep_model.py` 后重启 FastAPI。
- Android：回退 App 源码后重新构建并安装 APK。
