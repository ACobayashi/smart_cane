# DATABASE_MIGRATION

迁移脚本：`backend/migrations/20260723_full_chain_navigation_road_risk.sql`

## 新增表
- `navigation_sessions`
- `road_segments`
- `road_risk_observations`
- `road_traversals`
- `road_risk_scores`

## 执行方式
```bash
sqlite3 backend/smartcane.db < backend/migrations/20260723_full_chain_navigation_road_risk.sql
```

或直接重启 FastAPI 后端，`init_db()` 会自动创建缺失表和索引。

## 回滚说明
如需回滚，仅删除新增表即可，不影响既有 `risk_events`、`risk_points`、`device_state`。
```sql
DROP TABLE IF EXISTS road_risk_scores;
DROP TABLE IF EXISTS road_traversals;
DROP TABLE IF EXISTS road_risk_observations;
DROP TABLE IF EXISTS road_segments;
DROP TABLE IF EXISTS navigation_sessions;
```
