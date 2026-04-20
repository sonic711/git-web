# 需求 02：多專案定時推送

## 需求描述

系統必須支援多個廠商專案的 mapping 規則，並能依設定排程，自動將各專案來源 branch 推送到對應的目標 remote / branch。

## 目的

讓使用者不需要手動逐筆點擊同步，系統可在背景依排程自動完成多專案推送。

## 已確認規格

- 排程執行環境為本機 `Java 17` 服務。
- 不使用資料庫。
- 排程設定必須保存在可攜式設定檔中。
- 一筆 mapping 的同步邏輯沿用需求 01。
- `manualOnly=true` 的 mapping 不得進入排程。

## 功能拆解

此需求至少包含以下能力：

1. 為每筆 mapping 設定是否啟用排程。
2. 為每筆 mapping 設定排程間隔。
3. 背景自動觸發同步。
4. 保存每筆排程最近一次執行狀態。
5. 顯示下一次預計執行時間。

## 設定格式

建議直接內嵌在 `config/settings.json` 的 mapping 內：

```json
{
  "id": "map-sit",
  "name": "Vendor SIT to Target A SIT",
  "vendorRepoUrl": "https://vendor.example.com/project.git",
  "localRepoPath": "D:/git/vendor-project",
  "sourceBranch": "SIT",
  "targetRemoteId": "targetA",
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

## UI 需求

排程設定頁至少需提供：

- 是否啟用排程
- 排程間隔分鐘數
- manual-only 標記
- review-required 標記
- 最後執行時間
- 最後執行狀態
- 下次執行時間
- 立即執行按鈕

若使用者在 UI 修改排程開關或間隔：

1. 系統必須寫回 `config/settings.json`
2. 系統必須立即重新載入排程設定
3. 其他人複製該設定檔後應取得相同排程規則

## 排程流程

1. Java 服務啟動時載入設定檔。
2. Java 背景排程器定期掃描啟用中的 mapping。
3. 若 mapping 為 `manualOnly=true`，直接跳過。
4. 若某筆 mapping 已到達執行時間，觸發同步。
5. 若該 mapping 尚在執行中，跳過本輪，避免重複執行。
6. 執行完成後更新 runtime state。
7. 寫入 log。

## 驗證規則

排程執行前至少驗證：

1. mapping 必須為啟用狀態。
2. `schedule.enabled` 必須為 `true`。
3. `schedule.intervalMinutes` 必須大於 0。
4. 同一筆 mapping 不可重複執行。
5. `manualOnly=true` 時不得進入排程。

## 錯誤情境

系統至少要能處理：

- 排程設定缺漏
- 排程間隔不合法
- Java 服務重啟後 state 遺失或不一致
- 同一筆 mapping 重複觸發
- manual-only 規則誤入排程
- 排程推送失敗

## API 契約建議

- `GET /api/schedules`
- `PUT /api/mappings/{id}/schedule`
- `POST /api/mappings/{id}/sync`

## 驗收標準

此需求完成時，至少應滿足：

1. 可設定兩筆以上 mapping 的排程。
2. 系統可在背景自動觸發同步。
3. 每筆規則都能顯示最後執行時間與下次執行時間。
4. `manualOnly=true` 的規則不會被排程觸發。
5. UI 修改排程設定後會寫回主設定檔。
6. 排程執行結果會寫入 log。
7. 不依賴資料庫。
