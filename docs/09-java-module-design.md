# Java 模組設計

## 目的

定義 Java 17 專案的責任切分，避免一開始就把 HTTP、設定、Git、排程混在一起。

## 建議模組

### `AppServer`

職責：

- 啟動 HTTP server
- 註冊 routes
- 載入主要 services

### `ConfigService`

職責：

- 讀取 `config/settings.json`
- 驗證 schema
- 寫回設定檔
- 提供 in-memory config snapshot

輸出：

- `AppConfig`
- `RemoteConfig`
- `MappingConfig`

### `RuntimeStateService`

職責：

- 讀取 `state/runtime-state.json`
- 更新 mapping 執行狀態
- 保存 `lastRunAt`、`lastStatus`、`nextRunAt`

### `GitCommandRunner`

職責：

- 用 `ProcessBuilder` 執行 git
- 收集 exit code、stdout、stderr
- 統一 command timeout 與 error handling

輸出：

- `GitCommandResult`

### `GitRepositoryService`

職責：

- 驗證本機 repo
- clone repo
- fetch repo
- 驗證 branch 存在
- 取得 remote URL
- add/set target remote

### `DiffService`

職責：

- 產生來源與目標 branch 差異摘要
- 取得 commit list
- 回傳 UI 可顯示的摘要資料

### `SyncService`

職責：

- 執行單筆 mapping 同步
- 驗證 `allowForcePush`
- 驗證 `manualOnly`
- 驗證 `reviewRequired`
- 呼叫 GitRepositoryService / GitCommandRunner
- 更新 runtime state
- 寫入 log

### `SchedulerService`

職責：

- 啟動背景排程器
- 根據 `config/settings.json` 決定下次執行時間
- 跳過 `manualOnly=true` 規則
- 避免同一 mapping 重複執行

### `LogService`

職責：

- 寫入 `logs/YYYY-MM-DD.log`
- 以每日單檔方式持續追加執行紀錄
- 讀取指定每日 log 檔
- 啟動與寫入時刪除非當日 log

## Controller 建議

### `RemoteController`

- `GET /api/remotes`
- `POST /api/remotes`
- `PUT /api/remotes/{id}`

### `MappingController`

- `GET /api/mappings`
- `POST /api/mappings`
- `PUT /api/mappings/{id}`
- `POST /api/mappings/{id}/validate`
- `POST /api/mappings/{id}/diff`
- `POST /api/mappings/{id}/sync`

### `ScheduleController`

- `GET /api/schedules`
- `PUT /api/mappings/{id}/schedule`

### `LogController`

- `GET /api/logs/{logId}`

## Domain Model 建議

### `AppConfig`

- `int version`
- `OffsetDateTime updatedAt`
- `List<RemoteConfig> remotes`
- `List<MappingConfig> mappings`

### `RemoteConfig`

- `String id`
- `String name`
- `String baseUrl`
- `boolean enabled`

### `MappingConfig`

- `String id`
- `String name`
- `String vendorRepoUrl`
- `Path localWorkspaceRoot`
- `String localProjectName`
- `String sourceBranch`
- `String targetRemoteId`
- `String targetRepoName`
- `String targetBranch`
- `boolean sameBranchNameExpected`
- `boolean enabled`
- `boolean allowForcePush`
- `boolean manualOnly`
- `boolean reviewRequired`
- `ScheduleConfig schedule`

### `ScheduleConfig`

- `boolean enabled`
- `String type`
- `int intervalMinutes`

### `MappingRuntimeState`

- `OffsetDateTime lastRunAt`
- `String lastStatus`
- `String lastRunSource`
- `OffsetDateTime nextRunAt`
- `boolean running`
- `String lastLogPath`
  保存每日 log 檔名，例如 `2026-04-20.log`

## 開發順序建議

1. `ConfigService`
2. `RuntimeStateService`
3. `GitCommandRunner`
4. `GitRepositoryService`
5. `SyncService`
6. `DiffService`
7. `SchedulerService`
8. Controllers
9. Web UI

## 技術選型建議

若不額外引入大型框架，第一版可採：

- Java 原生 `com.sun.net.httpserver.HttpServer`
- JSON library 例如 Jackson 或 Gson
- `ScheduledExecutorService` 做背景排程

如果要更快整理 REST 與 JSON binding，也可用輕量框架，但前提仍是能在你的離線環境部署。
