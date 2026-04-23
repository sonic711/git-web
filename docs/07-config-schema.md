# 設定檔 Schema

## 目的

定義 `config/settings.json` 與 `state/runtime-state.json` 的正式結構，作為實作、驗證與匯入匯出的共同依據。

## 主設定檔

檔案位置：

- `config/settings.json`

用途：

- 保存可攜式業務設定
- 可由 UI 修改
- 可直接複製給其他人匯入

## 結構

```json
{
  "version": 1,
  "updatedAt": "2026-04-20T21:00:00+08:00",
  "remotes": [
    {
      "id": "target-uat",
      "name": "UAT",
      "baseUrl": "git@target.example.com:team/",
      "enabled": true
    }
  ],
  "mappings": [
    {
      "id": "map-uat",
      "name": "Vendor UAT to Target UAT",
      "vendorRepoUrl": "https://vendor.example.com/project.git",
      "localWorkspaceRoot": "D:/git-workspace",
      "localProjectName": "vendor-project",
      "sourceBranch": "UAT",
      "targetRemoteId": "target-uat",
      "targetRepoName": "project.git",
      "targetBranch": "UAT",
      "sameBranchNameExpected": true,
      "enabled": true,
      "allowForcePush": true,
      "manualOnly": true,
      "reviewRequired": true,
      "schedule": {
        "enabled": false,
        "type": "fixed-interval",
        "intervalMinutes": 30
      }
    }
  ]
}
```

## 欄位定義

### Root

- `version`: `integer`
  設定檔版本，第一版固定為 `1`
- `updatedAt`: `string`
  ISO-8601 時間字串，表示最近一次設定更新時間
- `remotes`: `array`
  目標 remote 清單
- `mappings`: `array`
  同步規則清單

### Remote

- `id`: `string`
  全域唯一，不可重複，建議使用小寫英數與 `-`
- `name`: `string`
  UI 顯示名稱，同時作為頁籤名稱
- `baseUrl`: `string`
  Git SSH base URL，例如 `git@target.example.com:team/`
- `enabled`: `boolean`
  是否啟用此 remote

### Mapping

- `id`: `string`
  全域唯一，不可重複
- `name`: `string`
  UI 顯示名稱
- `vendorRepoUrl`: `string`
  廠商 repo HTTPS URL
- `localWorkspaceRoot`: `string`
  放置多個專案的本地主目錄
- `localProjectName`: `string`
  本地專案資料夾名稱，實際 repo 路徑為 `localWorkspaceRoot/localProjectName`
- `sourceBranch`: `string`
  廠商來源分支
- `targetRemoteId`: `string`
  對應 `remotes[*].id`
- `targetRepoName`: `string`
  Mapping 補上的最後專案名稱，例如 `project.git`
- `targetBranch`: `string`
  目標分支
- `sameBranchNameExpected`: `boolean`
  若為 `true`，UI 建議預設來源與目標同名
- `enabled`: `boolean`
  規則是否啟用
- `allowForcePush`: `boolean`
  是否允許本次同步使用 `git push -f`
- `manualOnly`: `boolean`
  是否禁止排程，只允許手動同步
- `reviewRequired`: `boolean`
  是否要求同步前先看差異並人工確認
- `schedule`: `object`
  排程設定

### Schedule

- `enabled`: `boolean`
  是否啟用排程
- `type`: `string`
  第一版固定為 `fixed-interval`
- `intervalMinutes`: `integer`
  固定間隔分鐘數，必須大於 `0`

## 驗證規則

### Root 驗證

1. `version` 必須存在且為 `1`
2. `remotes` 不可為 `null`
3. `mappings` 不可為 `null`

### Remote 驗證

1. `id` 不可重複
2. `baseUrl` 不可為空
3. `baseUrl` 必須為有效 SSH Git URL，且應能和 `targetRepoName` 組成完整 remote URL

### Mapping 驗證

1. `id` 不可重複
2. `vendorRepoUrl` 不可為空
3. `localWorkspaceRoot` 不可為空
4. `localProjectName` 不可為空
5. `sourceBranch` 不可為空
6. `targetRemoteId` 必須能對應到既有 remote
7. `targetRepoName` 不可為空，且第一版要求以 `.git` 結尾
8. `targetBranch` 不可為空
9. `manualOnly=true` 時，`schedule.enabled` 必須為 `false`
10. `reviewRequired=true` 時，不得由排程觸發
11. `sameBranchNameExpected=true` 不代表必須同名，但 UI 應預設帶入同名值

## Runtime State

檔案位置：

- `state/runtime-state.json`

用途：

- 保存暫態執行資訊
- 不作為匯入匯出來源

## 結構

```json
{
  "mappingStates": {
    "map-uat": {
      "lastRunAt": "2026-04-20T21:30:00+08:00",
      "lastStatus": "success",
      "lastRunSource": "manual",
      "nextRunAt": null,
      "running": false,
      "lastLogPath": "2026-04-20.log"
    }
  }
}
```

## Runtime 欄位定義

- `lastRunAt`: `string | null`
- `lastStatus`: `success | failed | running | never`
- `lastRunSource`: `manual | schedule | null`
- `nextRunAt`: `string | null`
- `running`: `boolean`
- `lastLogPath`: `string | null`
  內容為每日 log 檔名，例如 `2026-04-20.log`
- `lastMessage`: `string | null`

## 匯入匯出規則

1. 匯出只處理 `config/settings.json`
2. 匯入後系統需重新驗證 schema
3. 匯入成功後需重新載入 remotes、mappings、scheduler
4. `state/runtime-state.json` 不可包含在正式匯出中
5. 舊版 `remotes[*].url` / `group` 設定匯入時可由程式轉成 `baseUrl` 與 `name`
