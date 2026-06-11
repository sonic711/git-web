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
- `ProjectConfig`
- `RuleConfig`

### `RuntimeStateService`

職責：

- 讀取 `state/runtime-state.json`
- 更新 rule 執行狀態
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
- 取得單一 commit 的異動檔案清單
- 回傳 UI 可顯示的摘要資料

### `GitService` 版本比對職責

職責：

- 目前由既有 `GitService` 實作，不另外建立 service
- 僅處理 `mode=sync` 規則
- fetch 最新來源與目標 branch
- 取得 source / target commit hash
- 取得 source / target tree hash
- 計算 `sourceOnlyCommits` 與 `targetOnlyCommits`
- 回傳 `IDENTICAL`、`CONTENT_IDENTICAL`、`DIFFERENT`、`TARGET_MISSING` 或 `CHECK_FAILED`

### `SyncService`

職責：

- 執行單筆 rule 同步
- 支援整支 branch 同步與 commit-based 同步兩種模式
- 支援 `download-only` 模式，只下載並對齊本地來源分支，不建立 target remote，也不 push；此模式需強制同步來源 remote tags，包含 tag 移動與刪除，且可使用 rule 層級 `downloadWorkspaceRoot` 覆寫下載主目錄
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
- 避免同一 rule 重複執行

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

### `ProjectController`

- `GET /api/projects`
- `POST /api/projects`
- `PUT /api/projects/{projectId}`
- `DELETE /api/projects/{projectId}`
- `PUT /api/projects/{projectId}/rules/{ruleId}`
- `DELETE /api/projects/{projectId}/rules/{ruleId}`

### `RuleController`

- `POST /api/rules/{ruleId}/validate`
- `POST /api/rules/{ruleId}/version-compare`
- `POST /api/rules/{ruleId}/diff`
- `GET /api/rules/{ruleId}/diff-cache`
- `POST /api/rules/{ruleId}/diff-cache/refresh`
- `GET /api/rules/{ruleId}/diff/commits/{commitId}/files`
- `POST /api/rules/{ruleId}/sync`
- `PUT /api/rules/{ruleId}/schedule`

### `ScheduleController`

- `GET /api/schedules`

### `LogController`

- `GET /api/logs/{logId}`

## Domain Model 建議

### `AppConfig`

- `int version`
- `OffsetDateTime updatedAt`
- `String localWorkspaceRoot`
- `List<RemoteConfig> remotes`
- `List<ProjectConfig> projects`

### `RemoteConfig`

- `String id`
- `String name`
- `String baseUrl`
- `boolean enabled`

### `ProjectConfig`

- `String id`
- `String name`
- `String vendorRepoUrl`
- `String localProjectName`
- `boolean enabled`
- `List<RuleConfig> rules`

### `RuleConfig`

- `String id`
- `String name`
- `String mode`
- `String sourceBranch`
- `String targetRemoteId`
- `String targetRepoName`
- `String targetBranch`
- `String downloadWorkspaceRoot`
- `boolean sameBranchNameExpected`
- `boolean enabled`
- `boolean allowForcePush`
- `boolean manualOnly`
- `boolean reviewRequired`
- `ScheduleConfig schedule`

`mode` 目前允許：

- `sync`
- `download-only`

舊設定若沒有 `mode`，預設視為 `sync`。

### `ScheduleConfig`

- `boolean enabled`
- `String type`
- `int intervalMinutes`

### `RuleRuntimeState`

- `OffsetDateTime lastRunAt`
- `String lastStatus`
- `String lastRunSource`
- `OffsetDateTime nextRunAt`
- `boolean running`
- `String lastLogPath`
  保存每日 log 檔名，例如 `2026-04-20.log`
- `String lastMessage`

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
