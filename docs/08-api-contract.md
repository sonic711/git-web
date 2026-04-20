# API 契約

## 目的

定義 Web UI 與 Java 17 Git Bridge 之間的 HTTP API，讓前後端可並行開發。

## 通用規則

- Base URL: `http://127.0.0.1:8080`
- Content-Type: `application/json`
- 所有時間欄位使用 ISO-8601
- 所有錯誤回應使用一致格式

## 錯誤格式

```json
{
  "error": {
    "code": "MAPPING_NOT_FOUND",
    "message": "Mapping does not exist",
    "details": {
      "mappingId": "map-uat"
    }
  }
}
```

## 錯誤碼建議

- `INVALID_REQUEST`
- `REMOTE_NOT_FOUND`
- `MAPPING_NOT_FOUND`
- `CONFIG_VALIDATION_FAILED`
- `REVIEW_CONFIRMATION_REQUIRED`
- `SCHEDULE_NOT_ALLOWED`
- `FORCE_PUSH_NOT_ALLOWED`
- `REPO_NOT_FOUND`
- `REPO_NOT_GIT`
- `REPO_REMOTE_MISMATCH`
- `BRANCH_NOT_FOUND`
- `GIT_COMMAND_FAILED`

## Remote API

### `GET /api/remotes`

用途：

- 取得所有 remote 設定

### `POST /api/remotes`

用途：

- 新增 remote

請求範例：

```json
{
  "id": "target-uat",
  "name": "UAT",
  "baseUrl": "git@target.example.com:team/",
  "enabled": true
}
```

### `PUT /api/remotes/{id}`

用途：

- 修改 remote
- 修改後必須寫回 `config/settings.json`

### `DELETE /api/remotes/{id}`

用途：

- 刪除 remote
- 若仍被 mapping 使用，後端必須拒絕

## Mapping API

### `GET /api/mappings`

用途：

- 取得所有 mapping 與基本狀態

回應範例：

```json
[
  {
    "id": "map-uat",
    "name": "Vendor UAT to Target UAT",
    "sourceBranch": "UAT",
    "targetBranch": "UAT",
    "manualOnly": true,
    "reviewRequired": true,
    "lastStatus": "success",
    "lastRunSource": "manual",
    "lastMessage": "Sync completed",
    "nextRunAt": null
  }
]
```

### `POST /api/mappings`

用途：

- 新增 mapping

### `PUT /api/mappings/{id}`

用途：

- 修改 mapping
- UI 修改 remote、branch、是否自動同步、review gate 等設定時都走此 API
- 成功後必須寫回 `config/settings.json`

### `DELETE /api/mappings/{id}`

用途：

- 刪除 mapping
- 成功後應一併移除對應 runtime state

請求範例：

```json
{
  "name": "Vendor SIT to Target SIT",
  "vendorRepoUrl": "https://vendor.example.com/project.git",
  "localWorkspaceRoot": "D:/git-workspace",
  "localProjectName": "vendor-project",
  "sourceBranch": "SIT",
  "targetRemoteId": "target-sit",
  "targetRepoName": "project.git",
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
```

## Validate API

### `POST /api/mappings/{id}/validate`

用途：

- 執行同步前檢查

回應範例：

```json
{
  "mappingId": "map-sit",
  "ok": true,
  "checks": [
    { "key": "mapping_enabled", "ok": true },
    { "key": "repo_ready", "ok": true },
    { "key": "branch_exists", "ok": true },
    { "key": "target_remote_template_exists", "ok": true },
    { "key": "target_repo_name_valid", "ok": true }
  ]
}
```

## Diff API

### `POST /api/mappings/{id}/diff`

用途：

- 取得來源與目標分支差異摘要
- `reviewRequired=true` 的 mapping，UI 應先呼叫此 API
- 後端需回傳由 `baseUrl + targetRepoName` 組成的完整目標 URL

回應範例：

```json
{
  "mappingId": "map-uat",
  "sourceBranch": "UAT",
  "targetBranch": "UAT",
  "targetRepoName": "project.git",
  "targetRemoteName": "UAT",
  "targetRemoteUrl": "git@target.example.com:team/project.git",
  "summary": {
    "aheadCommits": 2,
    "changedFiles": 6
  },
  "commits": [
    {
      "id": "abc1234",
      "title": "Fix payment validation"
    }
  ],
  "files": [
    {
      "status": "M",
      "path": "src/payment/Validator.java"
    }
  ]
}
```

## Sync API

### `POST /api/mappings/{id}/sync`

用途：

- 手動執行單筆同步

請求範例：

```json
{
  "forcePush": true,
  "reviewConfirmed": true
}
```

規則：

1. 若 `allowForcePush=false`，不得接受 `forcePush=true`
2. 若 `reviewRequired=true`，不得接受 `reviewConfirmed=false`
3. 若同步來源為排程，後端不得對 `manualOnly=true` 的 mapping 執行

回應範例：

```json
{
  "runId": "20260420T220000-map-uat",
  "mappingId": "map-uat",
  "status": "success",
  "targetRepoName": "project.git",
  "targetRemoteUrl": "git@target.example.com:team/project.git",
  "forcePush": true,
  "reviewConfirmed": true,
  "message": "Sync completed",
  "logPath": "logs/20260420T220000-map-uat.log"
}
```

## Schedule API

### `GET /api/schedules`

用途：

- 取得排程視圖資料

### `PUT /api/mappings/{id}/schedule`

用途：

- 修改某筆 mapping 的排程
- 成功後必須寫回 `config/settings.json`

請求範例：

```json
{
  "enabled": true,
  "type": "fixed-interval",
  "intervalMinutes": 30
}
```

規則：

1. 若 `manualOnly=true`，此 API 必須拒絕 `enabled=true`
2. 成功後必須重新載入 scheduler

## System API

### `POST /api/system/select-directory`

用途：

- 開啟本機資料夾選擇器
- 回傳使用者選到的絕對路徑

回應範例：

```json
{
  "path": "D:/git/vendor-project"
}
```

## Log API

### `GET /api/logs/{runId}`

用途：

- 取得單筆執行紀錄

回應範例：

```json
{
  "runId": "20260420T220000-map-uat",
  "status": "success",
  "triggerSource": "manual",
  "forcePush": true,
  "reviewConfirmed": true,
  "stdout": "...",
  "stderr": ""
}
```
