# Phase 2：差異快取機制

## 目的

解決 `查看差異` 在大型 repo、遠端較慢、差異檔案數量較多時載入過慢或 timeout 的問題。

Phase 2 的核心方向不是改變 Git 比對結果，而是將差異結果拆成：

1. 可快速讀取的本地快取摘要
2. 可按需重建的單檔 patch
3. 可手動刷新最新狀態的操作流程

## 設計目標

- 開啟差異頁時優先使用本地快取，降低等待時間
- 使用者可清楚知道目前看到的是快取結果還是最新重抓結果
- 單檔 diff 採懶載入，避免一次產生所有 patch
- 不使用資料庫，所有快取以本機檔案實作
- 不影響既有同步流程與 review-required 規則

## 範圍

### 本階段要做

- 差異摘要快取
- 單檔 patch 快取
- 快取時間與狀態顯示
- 手動刷新最新差異
- 快取失效與重建規則

### 本階段不做

- 檔案內容的語法高亮
- side-by-side diff
- 多版本 diff 歷史瀏覽
- 差異快取跨機器同步

## 快取檔案結構

建議新增：

- `cache/diff/<ruleId>/summary.json`
- `cache/diff/<ruleId>/files/<encoded-path>.patch`
- `cache/diff/<ruleId>/meta.json`

### `summary.json`

保存：

- `projectId`
- `projectName`
- `ruleId`
- `ruleName`
- `sourceBranch`
- `targetBranch`
- `targetRemoteName`
- `targetRemoteUrl`
- `summary.aheadCommits`
- `summary.changedFiles`
- `commits`
- `files`

其中 `files` 只保存：

- `status`
- `path`
- `oldPath`
- `displayPath`

不在摘要中保存完整 patch。

### `meta.json`

保存：

- `cachedAt`
- `sourceRef`
- `targetRef`
- `cacheStatus`
  可為：
  - `fresh`
  - `stale`
  - `refreshing`
  - `failed`
- `lastRefreshMessage`

### `files/<encoded-path>.patch`

每個檔案一個 patch 檔，內容即 `git diff --no-color --unified=3 ... -- <file>` 的結果。

檔名需做路徑編碼，避免：

- `/`
- `\`
- `:`
- 非法檔名字元

建議使用 URL-safe Base64 或 hash + index map。

## UI 流程

### 開啟差異頁

1. 前端先呼叫 `GET /api/rules/{id}/diff-cache`
2. 若有可用快取，立即顯示：
   - 檔案清單
   - commit list
   - 快取時間
   - 快取狀態
3. 若沒有可用快取：
   - 顯示 `尚未建立差異快取`
   - 提供 `抓取最新差異` 按鈕

### 手動抓取最新差異

1. 使用者按 `抓取最新差異`
2. 後端執行：
   - `fetch origin`
   - `fetch target remote`
   - 重建 `summary.json`
   - 清除舊 patch 快取
3. 完成後回傳新的摘要
4. UI 更新快取時間與檔案清單

### 點擊單一檔案

1. 前端呼叫 `GET /api/rules/{id}/diff-cache/file?path=...`
2. 後端流程：
   - 若 patch 快取存在，直接回傳
   - 若 patch 快取不存在，現場產生並存檔後回傳
3. UI 顯示右側 patch 內容

## API 設計

### `GET /api/rules/{id}/diff-cache`

用途：

- 讀取差異快取摘要

回應範例：

```json
{
  "ruleId": "rule-sit",
  "cacheStatus": "fresh",
  "cachedAt": "2026-04-21 15:30:00",
  "sourceBranch": "sit",
  "targetBranch": "sit",
  "summary": {
    "aheadCommits": 3,
    "changedFiles": 12
  },
  "commits": [
    {
      "id": "abc1234",
      "title": "Fix login validation"
    }
  ],
  "files": [
    {
      "status": "M",
      "path": "src/LoginService.java",
      "oldPath": null,
      "displayPath": "src/LoginService.java"
    }
  ]
}
```

### `POST /api/rules/{id}/diff-cache/refresh`

用途：

- 強制抓取最新差異並重建快取

回應：

- 與 `GET /api/rules/{id}/diff-cache` 相同

### `GET /api/rules/{id}/diff-cache/file`

Query:

- `path`
- `oldPath` 可選

用途：

- 讀取單一檔案的 patch
- 若 patch 不存在，可當場重建

回應範例：

```json
{
  "ruleId": "rule-sit",
  "path": "src/LoginService.java",
  "oldPath": null,
  "cacheHit": true,
  "patch": "diff --git a/src/LoginService.java b/src/LoginService.java\n..."
}
```

## 快取生命週期

### 建立時機

以下任一情況可建立或更新摘要快取：

- 使用者手動點 `抓取最新差異`
- 手動同步前執行 diff
- 自動同步完成後背景更新

### 失效時機

以下情況需將摘要標記為 `stale`：

- 規則設定被修改
- 專案 vendor repo URL 被修改
- 目標 remote / target repo / target branch 被修改
- 同步成功完成後
- 快取建立時間超過設定 TTL

### 清理策略

建議：

- `summary.json` 與 `meta.json` 保留最近一次
- `files/*.patch` 在摘要重建時全部刪除重建
- 若某條 rule 被刪除，對應 `cache/diff/<ruleId>` 一併刪除

## TTL 建議

第一版建議：

- 差異摘要 TTL：`5 分鐘`
- 單檔 patch TTL：依附摘要，摘要失效時一併失效

若超過 TTL：

- UI 顯示 `快取已過期`
- 仍可先顯示舊快取
- 使用者可決定是否按 `抓取最新差異`

## UI 顯示要求

差異頁需顯示：

- `快取時間`
- `快取狀態`
- `是否為最新`
- `抓取最新差異` 按鈕

差異頁右側若尚未點選檔案，顯示：

- `請從左側選擇檔案`

若點擊檔案但 patch 尚未載入，顯示：

- `載入檔案差異中...`

## 錯誤處理

### 摘要快取不存在

- 回傳 `404` 或明確狀態欄位
- UI 顯示 `尚未建立差異快取`

### 刷新失敗

- 保留舊快取
- `cacheStatus` 設為 `failed`
- `lastRefreshMessage` 記錄錯誤

### 單檔 patch 產生失敗

- 不影響其他檔案
- 右側只顯示該檔案錯誤訊息

## 驗收標準

1. 開啟差異頁時，若已有快取，應在不重新 fetch 的情況下快速顯示摘要
2. 點擊左側檔案時，右側才載入單檔 patch
3. `抓取最新差異` 可重建摘要與 patch 快取
4. 修改規則或同步完成後，快取需標記為 `stale`
5. 刪除規則時，對應差異快取需一併刪除
6. UI 必須能清楚分辨目前是快取內容還是最新刷新內容

## 實作順序建議

1. 建立 `DiffCacheService`
2. 實作摘要快取讀寫
3. 實作單檔 patch 快取讀寫
4. 新增 diff cache API
5. 前端差異頁改為讀快取 + 手動刷新
6. 補齊清理與失效機制
