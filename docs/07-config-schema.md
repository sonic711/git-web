# 設定檔 Schema

## 目的

定義 `config/settings.json` 與 `state/runtime-state.json` 的目前正式結構，作為 UI、Java 後端與匯入匯出的共同依據。

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
  "updatedAt": "2026-04-23T18:00:00+08:00",
  "localWorkspaceRoot": "D:/git-workspace",
  "remotes": [
    {
      "id": "target-sit",
      "name": "SIT",
      "baseUrl": "ssh://git@example.com:222/team/",
      "enabled": true
    }
  ],
  "projects": [
    {
      "id": "fsap-adm",
      "name": "fsap-adm",
      "vendorRepoUrl": "ssh://git@vendor.example.com/team/fsap-adm.git",
      "localProjectName": "fsap-adm",
      "enabled": true,
      "rules": [
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
      ]
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
- `localWorkspaceRoot`: `string | null`
  全局本地主目錄，所有專案共用
- `remotes`: `array`
  目標 remote 清單
- `projects`: `array`
  專案清單，每個專案底下包含多條同步規則

### Remote

- `id`: `string`
  全域唯一，不可重複，建議使用小寫英數與 `-`
- `name`: `string`
  UI 顯示名稱，同時作為 remote tab 名稱
- `baseUrl`: `string`
  Git SSH base URL，例如 `ssh://git@example.com:222/team/`
- `enabled`: `boolean`
  是否啟用此 remote

### Project

- `id`: `string`
  全域唯一，不可重複
- `name`: `string`
  UI 顯示名稱
- `vendorRepoUrl`: `string`
  廠商 repo URL，可為 `http://`、`https://`、`ssh://` 或 `git@host:path.git`
- `localProjectName`: `string`
  本地專案資料夾名稱，實際 repo 路徑為 `localWorkspaceRoot/localProjectName`
- `enabled`: `boolean`
  專案是否啟用
- `rules`: `array`
  該專案底下的同步規則清單

### Rule

- `id`: `string`
  全域唯一，不可重複
- `name`: `string`
  UI 顯示名稱
- `sourceBranch`: `string`
  廠商來源分支
- `targetRemoteId`: `string`
  對應 `remotes[*].id`
- `targetRepoName`: `string`
  目標 repo 名稱，例如 `fsap-adm.git`
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
  是否要求同步前先做 commit-based review 並人工確認
- `schedule`: `object`
  排程設定

### Schedule

- `enabled`: `boolean`
  是否啟用排程
- `type`: `string`
  目前固定為 `fixed-interval`
- `intervalMinutes`: `integer`
  固定間隔分鐘數，必須大於 `0`

## 驗證規則

### Root 驗證

1. `version` 必須存在且為 `1`
2. `remotes` 不可為 `null`
3. `projects` 不可為 `null`

### Remote 驗證

1. `id` 不可重複
2. `baseUrl` 不可為空
3. `baseUrl` 必須為有效 SSH Git URL，且應能和 `targetRepoName` 組成完整 remote URL

### Project 驗證

1. `id` 不可重複
2. `vendorRepoUrl` 不可為空
3. `localProjectName` 不可為空
4. `rules` 不可為 `null`

### Rule 驗證

1. `id` 不可重複
2. `sourceBranch` 不可為空
3. `targetRemoteId` 必須能對應到既有 remote
4. `targetRepoName` 不可為空，且目前要求以 `.git` 結尾
5. `targetBranch` 不可為空
6. `manualOnly=true` 時，`schedule.enabled` 必須為 `false`
7. `reviewRequired=true` 時，排程仍可保存，但排程同步不得繞過 review gate
8. `sameBranchNameExpected=true` 不代表必須同名，但 UI 應預設帶入同名值

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
    "rule-sit": {
      "lastRunAt": "2026-04-23T18:10:00+08:00",
      "lastStatus": "success",
      "lastRunSource": "manual",
      "nextRunAt": "2026-04-23T18:11:00+08:00",
      "running": false,
      "lastLogPath": "2026-04-23.log",
      "lastMessage": "Sync completed"
    }
  }
}
```

說明：

- `mappingStates` 這個 key 名稱仍保留舊版命名，但目前實際上是以 `ruleId` 為索引保存執行狀態。

## Runtime 欄位定義

- `lastRunAt`: `string | null`
- `lastStatus`: `success | failed | running | interrupted | never`
- `lastRunSource`: `manual | schedule | null`
- `nextRunAt`: `string | null`
- `running`: `boolean`
- `lastLogPath`: `string | null`
  內容為每日 log 檔名，例如 `2026-04-23.log`
- `lastMessage`: `string | null`

## 匯入匯出規則

1. 匯出只處理 `config/settings.json`
2. 匯入後系統需重新驗證 schema
3. 匯入成功後需重新載入 remotes、projects、rules 與 scheduler
4. `state/runtime-state.json` 不可包含在正式匯出中
5. 舊版 `mappings[*]` 設定匯入時可由程式自動轉成 `projects[*].rules[*]`
6. 舊版 `remotes[*].url` / `group` 設定匯入時可由程式轉成 `baseUrl` 與 `name`
