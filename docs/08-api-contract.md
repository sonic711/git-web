# API 契約

## 目的

定義 Web UI 與 Java 17 Git Bridge 之間目前實際使用的 HTTP API，讓前後端、文件與操作流程保持一致。

## 通用規則

- Base URL: `http://127.0.0.1:8080`
- Content-Type: `application/json`
- 所有時間欄位使用 ISO-8601
- 所有錯誤回應使用一致格式

## 錯誤格式

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Review confirmation is required",
    "details": {}
  }
}
```

## 目前實作中的錯誤碼

- `INVALID_REQUEST`
- `NOT_FOUND`
- `METHOD_NOT_ALLOWED`
- `DIFF_CACHE_NOT_FOUND`

## System API

### `GET /api/system/config`

用途：

- 取得全局本地主目錄設定

回應範例：

```json
{
  "localWorkspaceRoot": "D:/git-workspace"
}
```

### `PUT /api/system/config`

用途：

- 修改全局本地主目錄設定

請求範例：

```json
{
  "localWorkspaceRoot": "D:/git-workspace"
}
```

### `POST /api/system/select-directory`

用途：

- 開啟本機資料夾選擇器
- 回傳使用者選到的絕對路徑

回應範例：

```json
{
  "path": "D:/git-workspace"
}
```

## Remote API

### `GET /api/remotes`

用途：

- 取得所有 remote tab 設定

### `POST /api/remotes`

用途：

- 新增 remote

請求範例：

```json
{
  "id": "target-uat",
  "name": "UAT",
  "baseUrl": "ssh://git@example.com:222/team/",
  "enabled": true
}
```

### `PUT /api/remotes/{id}`

用途：

- 修改 remote
- 成功後寫回 `config/settings.json`

### `DELETE /api/remotes/{id}`

用途：

- 刪除 remote
- 若仍被規則使用，後端拒絕

## Project API

### `GET /api/projects`

用途：

- 取得所有專案與其規則、執行狀態、完整 target remote URL

回應範例：

```json
[
  {
    "id": "fsap-adm",
    "name": "fsap-adm",
    "vendorRepoUrl": "https://vendor.example.com/fsap-adm.git",
    "localProjectName": "fsap-adm",
    "localRepoPath": "D:/git-workspace/fsap-adm",
    "enabled": true,
    "rules": [
      {
        "id": "rule-uat",
        "name": "UAT -> UAT",
        "sourceBranch": "uat",
        "targetRemoteId": "target-uat",
        "targetRepoName": "fsap-adm.git",
        "targetBranch": "uat",
        "enabled": true,
        "allowForcePush": true,
        "manualOnly": true,
        "reviewRequired": true,
        "targetRemoteName": "UAT",
        "targetRemoteBaseUrl": "ssh://git@example.com:222/team/",
        "targetRemoteUrl": "ssh://git@example.com:222/team/fsap-adm.git",
        "lastRunAt": "2026-04-23T10:00:00+08:00",
        "lastStatus": "success",
        "lastRunSource": "manual",
        "lastMessage": "Sync completed",
        "lastLogPath": "2026-04-23.log",
        "nextRunAt": null,
        "running": false
      }
    ]
  }
]
```

### `POST /api/projects`

用途：

- 新增專案

請求範例：

```json
{
  "id": "fsap-adm",
  "name": "fsap-adm",
  "vendorRepoUrl": "https://vendor.example.com/fsap-adm.git",
  "localProjectName": "fsap-adm",
  "enabled": true,
  "rules": []
}
```

### `PUT /api/projects/{projectId}`

用途：

- 修改專案
- 成功後寫回 `config/settings.json`

### `DELETE /api/projects/{projectId}`

用途：

- 刪除專案
- 成功後一併移除該專案底下規則的 runtime state 與 diff cache

### `PUT /api/projects/{projectId}/rules/{ruleId}`

用途：

- 新增或修改單一規則
- 成功後寫回 `config/settings.json`

請求範例：

```json
{
  "id": "rule-sit",
  "name": "SIT -> SIT",
  "sourceBranch": "sit",
  "targetRemoteId": "target-sit",
  "targetRepoName": "fsap-adm.git",
  "targetBranch": "sit",
  "sameBranchNameExpected": true,
  "enabled": true,
  "allowForcePush": true,
  "manualOnly": false,
  "reviewRequired": false,
  "schedule": {
    "enabled": true,
    "type": "fixed-interval",
    "intervalMinutes": 1
  }
}
```

### `DELETE /api/projects/{projectId}/rules/{ruleId}`

用途：

- 刪除單一規則
- 成功後一併移除 runtime state 與 diff cache

## Rule API

### `POST /api/rules/{ruleId}/validate`

用途：

- 執行同步前檢查

回應範例：

```json
{
  "projectId": "fsap-adm",
  "ruleId": "rule-sit",
  "ok": true,
  "checks": [
    { "key": "project_enabled", "ok": true },
    { "key": "rule_enabled", "ok": true },
    { "key": "repo_path_exists", "ok": true },
    { "key": "repo_ready", "ok": true },
    { "key": "vendor_branch_exists", "ok": true },
    { "key": "target_remote_template_exists", "ok": true },
    { "key": "target_repo_name_valid", "ok": true }
  ]
}
```

### `POST /api/rules/{ruleId}/diff`

用途：

- 即時計算來源與目標分支的差異摘要
- 回傳 ahead commit 清單與整體 changed file summary

回應範例：

```json
{
  "projectId": "fsap-adm",
  "projectName": "fsap-adm",
  "ruleId": "rule-uat",
  "ruleName": "UAT -> UAT",
  "sourceBranch": "uat",
  "targetBranch": "uat",
  "targetRepoName": "fsap-adm.git",
  "targetRemoteName": "UAT",
  "targetRemoteUrl": "ssh://git@example.com:222/team/fsap-adm.git",
  "allowForcePush": true,
  "reviewRequired": true,
  "compareBase": "def5678",
  "compareHead": "abc1234",
  "summary": {
    "aheadCommits": 2,
    "changedFiles": 6
  },
  "commits": [
    {
      "id": "abc1234",
      "shortId": "abc1234",
      "author": "SeanLiu",
      "committedAt": "2026-04-23T10:00:00+08:00",
      "title": "Fix payment validation",
      "selectable": true
    }
  ],
  "files": [
    {
      "status": "M",
      "path": "src/payment/Validator.java",
      "oldPath": null,
      "displayPath": "src/payment/Validator.java"
    }
  ]
}
```

### `GET /api/rules/{ruleId}/diff-cache`

用途：

- 讀取該規則最近一次 commit review 摘要快取

### `POST /api/rules/{ruleId}/diff-cache/refresh`

用途：

- 重新抓取最新 commit review 摘要並更新快取

### `GET /api/rules/{ruleId}/diff/commits/{commitId}/files`

用途：

- 讀取單一 commit 的異動檔案清單
- 若快取不存在，後端現場計算後回寫快取

回應範例：

```json
{
  "commitId": "abc1234",
  "title": "Fix payment validation",
  "files": [
    {
      "status": "M",
      "path": "src/payment/Validator.java",
      "oldPath": null,
      "displayPath": "src/payment/Validator.java"
    }
  ]
}
```

### `POST /api/rules/{ruleId}/sync`

用途：

- 手動執行單筆同步
- 可做整支 branch 同步，也可用 `selectedCommitIds` 做 commit-based sync

請求範例：

```json
{
  "forcePush": true,
  "reviewConfirmed": true,
  "selectedCommitIds": ["abc1234", "def5678"]
}
```

規則：

1. 若 `allowForcePush=false`，不得接受 `forcePush=true`
2. 若 `reviewRequired=true`，不得接受 `reviewConfirmed=false`
3. 若 `reviewRequired=true`，目前要求 `selectedCommitIds` 不可為空
4. 若同步來源為排程，後端不得對 `manualOnly=true` 的規則執行
5. 若帶入 `selectedCommitIds`，後端以目標 branch 為基準建立暫時分支並 `cherry-pick` 這批 commit
6. `selectedCommitIds` 會由後端依來源 branch 歷史順序重新排序後執行
7. 若任一 selected commit 無法乾淨 `cherry-pick` 到目標 branch，本次同步會失敗並中止
8. 目前 API 不提供互動式衝突解決，也不保證單一 commit 可獨立同步

commit-based sync 錯誤情境：

- 選取的 commit 依賴未選取的前置 commit
- 目標 branch 已修改同一段內容，導致 cherry-pick conflict
- 目標 branch 不存在，目前 commit-based sync 會拒絕執行

回應範例：

```json
{
  "runId": "20260423T181000-rule-uat",
  "projectId": "fsap-adm",
  "projectName": "fsap-adm",
  "ruleId": "rule-uat",
  "ruleName": "UAT -> UAT",
  "status": "success",
  "sourceBranch": "uat",
  "localRepoPath": "D:/git-workspace/fsap-adm",
  "targetRemoteId": "target-uat",
  "targetRepoName": "fsap-adm.git",
  "targetBranch": "uat",
  "targetRemoteUrl": "ssh://git@example.com:222/team/fsap-adm.git",
  "forcePush": true,
  "reviewConfirmed": true,
  "selectedCommitIds": ["abc1234", "def5678"],
  "message": "Sync completed",
  "logPath": "2026-04-23.log"
}
```

### `PUT /api/rules/{ruleId}/schedule`

用途：

- 修改某筆規則的排程
- 成功後寫回 `config/settings.json`

請求範例：

```json
{
  "enabled": true,
  "type": "fixed-interval",
  "intervalMinutes": 30
}
```

## Schedule API

### `GET /api/schedules`

用途：

- 取得所有規則的排程檢視資料

## Log API

### `GET /api/logs/{logId}`

用途：

- 讀取指定每日 log 檔
- 目前 log 檔名格式為 `YYYY-MM-DD.log`

回應範例：

```json
{
  "logId": "2026-04-23.log",
  "content": "===== 20260423T181000-rule-uat =====\n..."
}
```
