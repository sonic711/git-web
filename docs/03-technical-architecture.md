# 技術架構

## 架構目標

在只有 `Java 17`、無資料庫、且 UI 必須是網頁的條件下，提供可離線部署的 Git 操作系統。

## 架構結論

系統採兩層式架構：

1. Web UI：`HTML + JavaScript`
2. Git Bridge：`Java 17` localhost HTTP 服務

Git Bridge 透過 `ProcessBuilder` 呼叫系統安裝的 `git` 指令。

## 架構圖

```text
+-----------------------------+
| Web UI (HTML + JavaScript)  |
| - Mapping 列表              |
| - Mapping 設定              |
| - 執行結果 / Log            |
+-------------+---------------+
              |
              | HTTP (localhost)
              v
+-----------------------------+
| Java 17 Git Bridge Service  |
| - 讀取 JSON 設定            |
| - 寫回 JSON 設定            |
| - 讀取 / 寫入 state 檔      |
| - 驗證本機目錄              |
| - clone / fetch / push      |
| - 管理 remote               |
| - 背景排程器                |
| - diff / review gate        |
| - 回傳 JSON 結果            |
| - 寫入 log                  |
+-------------+---------------+
              |
              v
+-----------------------------+
| Local Files                 |
| - config/settings.json      |
| - state/runtime-state.json  |
| - logs/YYYY-MM-DD.log       |
+-----------------------------+
```

## 模組說明

## 1. Web UI

職責：

- 顯示 mapping 規則
- 提供新增 / 編輯設定頁
- 提供排程設定頁
- 提供 remote 設定頁
- 讓使用者選擇是否 `Force Push`
- 提供 diff 檢視與人工確認操作
- 觸發單筆同步
- 顯示執行結果與 log

限制：

- 不直接執行 Git
- 不直接讀寫任意本機資料夾

## 2. Java 17 Git Bridge Service

職責：

- 提供本機 HTTP API
- 讀取與寫入 JSON 設定
- 讀取與寫入 runtime state
- 驗證 repo 目錄與 branch
- 執行 `git clone`、`git fetch`、`git remote add/set-url`、`git push`
- 背景輪詢排程並觸發同步
- 產生來源與目標之間的差異摘要
- 驗證 review-required mapping 的人工確認狀態
- 將 shell 執行結果轉為結構化 JSON
- 寫入執行 log

技術要求：

- 使用 `Java 17`
- 透過 `ProcessBuilder` 呼叫系統 `git`
- 不依賴 Node.js
- 不依賴資料庫

## 3. 設定檔

系統設定採檔案式儲存。

### `config/settings.json`

主設定檔採單一 JSON 檔，便於直接複製給其他人：

```json
{
  "version": 1,
  "remotes": [
    {
      "id": "targetA",
      "name": "SIT",
      "baseUrl": "git@target.example.com:team/"
    }
  ],
  "mappings": [
    {
      "id": "map-sit",
      "name": "Vendor SIT to Target A SIT",
      "vendorRepoUrl": "https://vendor.example.com/project.git",
      "localWorkspaceRoot": "D:/git-workspace",
      "localProjectName": "vendor-project",
      "sourceBranch": "SIT",
      "targetRemoteId": "targetA",
      "targetRepoName": "project-a.git",
      "targetBranch": "SIT",
      "sameBranchNameExpected": true,
      "enabled": true,
      "allowForcePush": true,
      "manualOnly": false,
      "reviewRequired": false,
      "schedule": {
        "enabled": true,
        "type": "fixed-interval",
        "intervalMinutes": 30
      }
    }
  ]
}
```

欄位說明：

- `version`：設定檔版本
- `remotes`：目標 remote 清單
- `mappings`：同步規則清單
- `remotes[*].name`：Remote 頁籤名稱
- `remotes[*].baseUrl`：Remote 的 SSH base URL
- `mappings[*].targetRepoName`：實際專案名稱，例如 `project-a.git`
- `sameBranchNameExpected`：是否預期來源與目標分支同名，UI 可據此自動帶入預設值
- `manualOnly`：是否僅允許手動同步
- `reviewRequired`：是否要求同步前先看差異並人工確認
- `schedule.enabled`：是否啟用此規則的排程
- `schedule.type`：排程型別，第一版建議固定為 `fixed-interval`
- `schedule.intervalMinutes`：排程間隔分鐘數

規則限制：

- 若 `manualOnly=true`，則 `schedule.enabled` 必須為 `false`
- 若 `reviewRequired=true`，則手動同步前必須先取得 diff 並完成人工確認

### `state/runtime-state.json`

runtime state 與主設定檔分離保存，避免複製設定檔時夾帶暫態資訊：

```json
{
  "mappingStates": {
    "map-sit": {
      "lastRunAt": "2026-04-20T19:30:00+08:00",
      "lastStatus": "success",
      "nextRunAt": "2026-04-20T20:00:00+08:00",
      "running": false
    }
  }
}
```

## 4. Log

每次同步產生一筆 log，至少包含：

- 執行時間
- 規則 ID
- 是否使用 force
- 執行的 Git 指令
- stdout
- stderr
- 結果狀態
- 觸發來源：手動或排程
- 是否經過 review 確認

## API 設計

建議 API：

- `GET /api/remotes`
- `POST /api/remotes`
- `PUT /api/remotes/{id}`
- `POST /api/system/select-directory`
- `GET /api/mappings`
- `POST /api/mappings`
- `PUT /api/mappings/{id}`
- `GET /api/schedules`
- `PUT /api/mappings/{id}/schedule`
- `POST /api/mappings/{id}/diff`
- `GET /api/mappings/{id}/diff/commits/{commitId}/files`
- `POST /api/mappings/{id}/validate`
- `POST /api/mappings/{id}/sync`
- `GET /api/logs/{logId}`

設定維護要求：

- 所有由 UI 修改的設定都必須寫回 `config/settings.json`
- 設定寫回成功後，後端必須重新載入 in-memory 規則與排程
- 匯出的主設定檔不得依賴 `state/runtime-state.json`

## `POST /api/mappings/{id}/sync`

請求體建議：

```json
{
  "forcePush": true,
  "reviewConfirmed": false,
  "selectedCommitIds": ["abc1234", "def5678"]
}
```

說明：

- `forcePush=true` 表示本次同步需加上 `git push -f`
- 若規則本身 `allowForcePush=false`，後端必須拒絕此請求
- 若規則本身 `reviewRequired=true`，則 `reviewConfirmed` 必須為 `true`
- 若帶入 `selectedCommitIds`，後端僅同步這批 commit，不執行整支來源 branch 的全量 push
- `selectedCommitIds` 應依來源 branch 的歷史順序傳入

## `POST /api/mappings/{id}/diff`

回應體建議：

```json
{
  "mappingId": "map-uat",
  "sourceBranch": "UAT",
  "targetBranch": "UAT",
  "summary": {
    "aheadCommits": 3,
    "changedFiles": 12
  },
  "commits": [
    {
      "id": "abc1234",
      "title": "Fix payment validation",
      "author": "SeanLiu",
      "committedAt": "2026-04-23T10:00:00+08:00",
      "selectable": true
    }
  ]
}
```

說明：

- 此 API 回傳目前來源分支相對目標分支的 ahead commit 清單
- UI 應以 commit 清單作為 review 與選擇同步內容的主軸
- 本階段不提供實際 patch 內容

## `GET /api/mappings/{id}/diff/commits/{commitId}/files`

回應體建議：

```json
{
  "mappingId": "map-uat",
  "commitId": "abc1234",
  "title": "Fix payment validation",
  "files": [
    {
      "status": "M",
      "path": "src/payment/Validator.java"
    },
    {
      "status": "A",
      "path": "src/payment/ValidatorTest.java"
    }
  ]
}
```

## Git 執行策略

同步時建議使用以下流程：

1. 若實際 repo 路徑不存在，執行 clone。
2. 若存在，驗證為 Git repo。
3. 驗證既有 repo 的 `origin` URL 是否與 `vendorRepoUrl` 一致。
4. 執行 `git fetch origin --prune`。
5. 驗證 `origin/<sourceBranch>` 存在。
6. 將本地來源分支強制對齊 `origin/<sourceBranch>`，再執行 `git pull --ff-only origin <sourceBranch>`。
7. 建立或更新系統管理的 target remote。
8. 若 `reviewRequired=true`，先要求 UI 透過 diff API 顯示 ahead commit 清單。
9. 使用者可挑選一個或多個 commit，並查看單一 commit 的異動檔案清單。
10. 若本次為 commit-based push，後端以目標 branch 為基準建立暫時同步分支，依順序套用選取的 commit。
11. 執行 `git push` 或 `git push -f`。

## 排程執行策略

Java 服務需內建背景排程器：

1. 啟動時讀取 `config/settings.json` 與 `state/runtime-state.json`。
2. 以固定頻率檢查哪些 mapping 已到達執行時間。
3. 跳過 `manualOnly=true` 或 `schedule.enabled=false` 的 mapping。
4. 逐筆觸發同步，不可併發執行同一筆 mapping。
5. 執行完成後回寫 `lastRunAt`、`lastStatus`、`nextRunAt`。
6. 每次排程執行都必須寫入 log。

## Remote 命名策略

系統內部為目標 remote 產生固定名稱：

- `sync_target_<mappingId>`

例如：

- `sync_target_map-sit`
- `sync_target_map-uat`

這可避免與 repo 原有 remote 名稱衝突。

## 安全性限制

為避免變成任意命令執行器，後端必須：

- 只允許白名單 Git 指令
- 不接受使用者直接輸入 shell 命令
- 驗證本機路徑是否合法
- 不把密碼明文存入設定檔

## 目前規格邊界

目前規格支援：

- 單機部署
- 單人使用優先
- 手動執行單筆 mapping
- 背景排程執行多筆 mapping
- 特定 mapping 可設定為 manual-only 與 review-required
- 廠商來源為 `HTTPS`
- 目標 remote 為 `SSH`
- 單一 JSON 主設定檔
- runtime state 檔
- 文字 log
